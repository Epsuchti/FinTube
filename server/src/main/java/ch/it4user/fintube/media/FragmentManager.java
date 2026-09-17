package ch.it4user.fintube.media;

import ch.it4user.fintube.core.Database;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistent fragment cache shared by playback and background filling.
 * Every logical fragment has one single-flight future, atomic file publication,
 * and active reader/writer accounting used by eviction.
 */
@Service
public class FragmentManager {
  /** Coordinates lease acquisition with maintenance's check-and-unlink step. */
  public static final Object EVICTION_LOCK = new Object();
  final Database db;
  final HttpClient http;
  /** One upstream operation per logical fragment. A low-priority operation can
   * be promoted by a foreground waiter without starting a second request. */
  final ConcurrentHashMap<String, Inflight> inflight = new ConcurrentHashMap<>();
  private final AtomicInteger interactive = new AtomicInteger();

  private static final class Inflight {
    final CompletableFuture<Path> result = new CompletableFuture<>();
    final boolean high;
    volatile boolean promoted;

    Inflight(boolean high) { this.high = high; }
  }

  @Autowired
  public FragmentManager(Database db) {
    this(db, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
  }

  FragmentManager(Database db, HttpClient http) {
    this.db = db;
    this.http = http;
  }

  /** Backwards-compatible progressive fragment entry point. */
  public Path get(String video, String format, String fragment, URI source, boolean high) throws Exception {
    return get(video, format, fragment, source, null, high);
  }

  /**
   * Get a logical fragment. For DASH pairs the two tracks are fetched once and
   * stream-copy remuxed into the same cached file. The cache key deliberately
   * excludes user identity.
   */
  public Path get(String video, String format, String fragment, URI videoSource,
                 URI audioSource, boolean high) throws Exception {
    if (high) interactive.incrementAndGet();
    try {
      Path cached = cached(video, format, fragment);
      if (cached != null) return cached;
      if (!high) {
        waitForInteractive();
        // A high-priority request may have completed while this filler was
        // yielding. Recheck before claiming a new single-flight slot.
        cached = cached(video, format, fragment);
        if (cached != null) return cached;
      }
      String key = key(video, format, fragment);
      Path target = target(video, format, fragment);
      Files.createDirectories(target.getParent());
      markActive(video, format, 1, 0);
      Inflight created = new Inflight(high);
      Inflight active = inflight.putIfAbsent(key, created);
      if (active != null) {
        // A foreground consumer joining a background request promotes that
        // request. It must finish promptly for both consumers; otherwise the
        // background worker could yield to the foreground waiter forever.
        if (high && !active.high) active.promoted = true;
        markActive(video, format, -1, 0);
        try { return active.result.get(); }
        catch (java.util.concurrent.ExecutionException e) {
          Throwable cause = e.getCause();
          if (cause instanceof Exception exception) throw exception;
          if (cause instanceof Error error) throw error;
          throw e;
        }
      }
      try {
        // A high-priority request may arrive while a low-priority worker was
        // waiting. The low worker already owns this single-flight entry, so it
        // is safe to finish it; the high request joins without duplication.
        Path tmpVideo = temporary(target);
        download(videoSource, tmpVideo, high, created);
        Path tmpFinal = tmpVideo;
        if (audioSource != null) {
          Path tmpAudio = temporary(target);
          try {
            download(audioSource, tmpAudio, high, created);
            tmpFinal = remux(tmpVideo, tmpAudio, temporary(target));
          } finally {
            Files.deleteIfExists(tmpAudio);
            Files.deleteIfExists(tmpVideo);
          }
        }
        Files.move(tmpFinal, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        upsert(video, format, fragment, target);
        created.result.complete(target);
        return target;
      } catch (Exception e) {
        // Temporary files are never valid cache entries. Remove this request's
        // files immediately; startup maintenance also removes leftovers from a
        // process crash.
        cleanupTemporary(target);
        created.result.completeExceptionally(e);
        throw e;
      } finally {
        inflight.remove(key, created);
        markActive(video, format, -1, 0);
      }
    } finally {
      if (high) interactive.decrementAndGet();
    }
  }

  /** Open a cached fragment with a lease that protects it from eviction. */
  public InputStream open(String video, String format, String fragment, URI source, boolean high) throws Exception {
    return open(video, format, fragment, source, null, high);
  }

  public InputStream open(String video, String format, String fragment, URI videoSource,
                          URI audioSource, boolean high) throws Exception {
    // Acquire the lease before resolving/fetching the path. This closes the
    // eviction race where maintenance could delete a fragment between the
    // cache lookup and opening the stream.
    markActive(video, format, 0, 1);
    try {
      Path path = get(video, format, fragment, videoSource, audioSource, high);
      InputStream delegate = Files.newInputStream(path);
      return new FilterInputStream(delegate) {
        private boolean closed;
        @Override public void close() throws IOException {
          if (!closed) {
            closed = true;
            try { super.close(); } finally { markActiveQuietly(video, format, 0, -1); }
          }
        }
      };
    } catch (Exception e) {
      markActive(video, format, 0, -1);
      throw e;
    }
  }

  /** Mark a format complete when all expected fragments have been published. */
  public void markComplete(String video, String format, int expectedFragments) throws SQLException {
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "SELECT COUNT(*) FROM cached_fragments WHERE video_id=? AND format_key=? AND completed=1")) {
      p.setString(1, video); p.setString(2, format);
      try (ResultSet r = p.executeQuery()) {
        if (r.next() && expectedFragments > 0 && r.getInt(1) >= expectedFragments) {
          try (PreparedStatement u = c.prepareStatement("UPDATE cache_entries SET status='COMPLETE',completed_at=?,last_accessed_at=? WHERE video_id=?")) {
            u.setString(1, Database.now()); u.setString(2, Database.now()); u.setString(3, video); u.executeUpdate();
          }
        }
      }
    }
  }

  public boolean isCached(String video, String format, String fragment) throws SQLException {
    return cached(video, format, fragment) != null;
  }

  private Path cached(String video, String format, String fragment) throws SQLException {
    Path result = null;
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "SELECT path FROM cached_fragments WHERE video_id=? AND format_key=? AND fragment_id=? AND completed=1")) {
      p.setString(1, video); p.setString(2, format); p.setString(3, fragment);
      try (ResultSet r = p.executeQuery()) {
        if (!r.next()) return null;
        result = Path.of(r.getString(1));
        if (!Files.isRegularFile(result) || Files.size(result) == 0) {
          try (PreparedStatement d = c.prepareStatement("DELETE FROM cached_fragments WHERE video_id=? AND format_key=? AND fragment_id=?")) {
            d.setString(1, video); d.setString(2, format); d.setString(3, fragment); d.executeUpdate();
          }
          return null;
        }
      } catch (IOException e) {
        return null;
      }
    }
    // Close the SELECT connection/cursor before opening the independent touch
    // update connection to keep cache reads and writes short-lived.
    touch(video, format, fragment);
    return result;
  }

  private Path target(String video, String format, String fragment) {
    return db.cacheRoot.resolve(safe(video)).resolve(safe(format)).resolve(safe(fragment) + ".part");
  }

  private static Path temporary(Path target) {
    return target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
  }

  private static void cleanupTemporary(Path target) {
    try {
      Path parent = target.getParent();
      if (parent == null || !Files.isDirectory(parent)) return;
      try (var files = Files.list(parent)) {
        files.filter(p -> p.getFileName().toString().startsWith(target.getFileName() + ".tmp-")).forEach(p -> {
          try { Files.deleteIfExists(p); } catch (IOException ignored) { }
        });
      }
    } catch (IOException ignored) { }
  }

  private void download(URI source, Path target, boolean high, Inflight owner) throws Exception {
    if (source == null) throw new IllegalArgumentException("missing source URL");
    HttpRequest request = HttpRequest.newBuilder(source).GET().build();
    // Read in bounded chunks instead of BodyHandlers.ofFile so a low-priority
    // transfer can yield between reads when unrelated playback is active.
    HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 410)
      throw new ExpiredSourceException();
    if (response.statusCode() < 200 || response.statusCode() > 299)
      throw new IllegalStateException("upstream status " + response.statusCode());
    long started = System.nanoTime();
    long bytes = 0;
    try (InputStream in = response.body(); var out = Files.newOutputStream(target)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = in.read(buffer)) != -1) {
        if (!high && !owner.promoted) {
          awaitForegroundTurn();
          paceBackground(bytes, started, owner);
        }
        out.write(buffer, 0, read);
        bytes += read;
      }
    }
    if (bytes == 0 || !Files.isRegularFile(target) || Files.size(target) == 0)
      throw new IOException("empty upstream fragment");
  }

  /** Wait while an unrelated interactive request is active. */
  private void awaitForegroundTurn() throws InterruptedException {
    while (interactive.get() > 0) Thread.sleep(10);
  }

  /** Apply the configured low-priority bandwidth limit while reading. */
  private void paceBackground(long bytes, long started, Inflight owner) throws InterruptedException {
    try {
      double mbps = Double.parseDouble(db.settings(false)
          .getOrDefault("background_download_max_mbps", "75"));
      if (mbps <= 0 || bytes <= 0) return;
      long minimum = (long) (bytes * 8_000_000_000d / (mbps * 1_000_000d));
      long wait = minimum - (System.nanoTime() - started);
      while (wait > 0 && !owner.promoted) {
        long slice = Math.min(wait, 10_000_000L);
        Thread.sleep(slice / 1_000_000, (int) (slice % 1_000_000));
        wait = minimum - (System.nanoTime() - started);
      }
    } catch (NumberFormatException | SQLException ignored) {
      // Settings are validated at the API boundary; retain a safe fallback if
      // a legacy database contains an invalid value.
    }
  }

  private Path remux(Path video, Path audio, Path output) throws Exception {
    String ffmpeg;
    try { ffmpeg = db.settings(false).getOrDefault("ffmpeg_path", "ffmpeg"); }
    catch (SQLException e) { ffmpeg = "ffmpeg"; }
    Process process = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-i", video.toString(),
        "-i", audio.toString(), "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-f", "mpegts", "-y", output.toString())
        .redirectErrorStream(true).start();
    byte[] outputLog = process.getInputStream().readAllBytes();
    if (process.waitFor() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0)
      throw new IOException("stream-copy remux failed" + (outputLog.length == 0 ? "" : ": " + new String(outputLog)));
    return output;
  }

  private void upsert(String video, String format, String fragment, Path path) throws SQLException, IOException {
    String now = Database.now();
    try (Connection c = db.open(); PreparedStatement e = c.prepareStatement(
        "UPDATE cache_entries SET format_key=?,status=CASE WHEN status='COMPLETE' THEN status ELSE 'PARTIAL' END,last_accessed_at=? WHERE video_id=?")) {
      e.setString(1, format); e.setString(2, now); e.setString(3, video);
      if (e.executeUpdate()==0) try (PreparedStatement i=c.prepareStatement("INSERT INTO cache_entries(video_id,format_key,status,last_accessed_at,active_readers,active_writers) VALUES(?,?, 'PARTIAL',?,0,0)")) { i.setString(1,video); i.setString(2,format); i.setString(3,now); i.executeUpdate(); }
    }
    try (Connection c = db.open(); PreparedStatement x = c.prepareStatement(
        "MERGE INTO cached_fragments(video_id,format_key,fragment_id,path,size_bytes,completed,last_accessed_at) KEY(video_id,format_key,fragment_id) VALUES(?,?,?,?,?,?,?)")) {
      x.setString(1, video); x.setString(2, format); x.setString(3, fragment); x.setString(4, path.toString());
      x.setLong(5, Files.size(path)); x.setInt(6, 1); x.setString(7, now); x.executeUpdate();
    }
  }

  private void touch(String video, String format, String fragment) throws SQLException {
    String now = Database.now();
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "UPDATE cached_fragments SET last_accessed_at=? WHERE video_id=? AND format_key=? AND fragment_id=?")) {
      p.setString(1, now); p.setString(2, video); p.setString(3, format); p.setString(4, fragment); p.executeUpdate();
    }
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement("UPDATE cache_entries SET last_accessed_at=? WHERE video_id=?")) {
      p.setString(1, now); p.setString(2, video); p.executeUpdate();
    }
  }

  private void markActive(String video, String format, int writers, int readers) throws SQLException {
    synchronized (EVICTION_LOCK) {
      try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
          "UPDATE cache_entries SET format_key=?,active_readers=GREATEST(0,active_readers+?),active_writers=GREATEST(0,active_writers+?),last_accessed_at=? WHERE video_id=?")) {
        p.setString(1, format); p.setInt(2, readers); p.setInt(3, writers); p.setString(4, Database.now()); p.setString(5, video);
        if (p.executeUpdate()==0) try (PreparedStatement i=c.prepareStatement("INSERT INTO cache_entries(video_id,format_key,status,last_accessed_at,active_readers,active_writers) VALUES(?,?, 'PARTIAL',?,?,?)")) { i.setString(1,video); i.setString(2,format); i.setString(3,Database.now()); i.setInt(4,Math.max(readers,0)); i.setInt(5,Math.max(writers,0)); i.executeUpdate(); }
      }
    }
  }

  private void markActiveQuietly(String video, String format, int writers, int readers) {
    try { markActive(video, format, writers, readers); } catch (SQLException ignored) { }
  }

  private void waitForInteractive() throws InterruptedException {
    awaitForegroundTurn();
  }

  private static String key(String video, String format, String fragment) { return video + "/" + format + "/" + fragment; }
  static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
  public static class ExpiredSourceException extends Exception { }
}
