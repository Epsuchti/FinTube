package ch.it4user.fintube.media;

import ch.it4user.fintube.core.ApplicationPaths;
import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.persistence.entities.CacheEntryEntity;
import ch.it4user.fintube.persistence.repositories.CacheEntryRepository;
import ch.it4user.fintube.persistence.entities.CachedFragmentEntity;
import ch.it4user.fintube.persistence.entities.CachedFragmentId;
import ch.it4user.fintube.persistence.repositories.CachedFragmentRepository;
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
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent fragment cache shared by playback and background filling.
 * Every logical fragment has one single-flight future, atomic file publication,
 * and active reader/writer accounting used by eviction.
 */
@Service
public class FragmentManager {
  private static final Logger LOG = LoggerFactory.getLogger(FragmentManager.class);
  /** Coordinates lease acquisition with maintenance's check-and-unlink step. */
  public static final Object EVICTION_LOCK = new Object();
  final ApplicationPaths paths;
  final SettingsService settingsService;
  final CacheEntryRepository cacheEntries;
  final CachedFragmentRepository cachedFragments;
  final ProxiedHttpClient externalHttp;
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
  public FragmentManager(ApplicationPaths paths, SettingsService settingsService,
                         CacheEntryRepository cacheEntries, CachedFragmentRepository cachedFragments,
                         ProxiedHttpClient externalHttp) {
    this.paths = paths;
    this.settingsService = settingsService;
    this.cacheEntries = cacheEntries;
    this.cachedFragments = cachedFragments;
    this.externalHttp = externalHttp;
  }

  FragmentManager(ApplicationPaths paths, SettingsService settingsService,
                  CacheEntryRepository cacheEntries, CachedFragmentRepository cachedFragments,
                  HttpClient http) {
    this(paths, settingsService, cacheEntries, cachedFragments,
        new ProxiedHttpClient(settingsService, http));
  }

  /** Backwards-compatible progressive fragment entry point. */
  public Path get(String video, String format, String fragment, URI source, boolean high) throws Exception {
    return get(video, format, fragment, source, null, 0d, high);
  }

  /**
   * Get a logical fragment. For DASH pairs the two tracks are fetched once and
   * stream-copy remuxed into the same cached file. The cache key deliberately
   * excludes user identity.
   */
  public Path get(String video, String format, String fragment, URI videoSource,
                 URI audioSource, boolean high) throws Exception {
    return get(video, format, fragment, videoSource, audioSource, 0d, high);
  }

  public Path get(String video, String format, String fragment, URI videoSource,
                  URI audioSource, double startSeconds, boolean high) throws Exception {
    if (high) interactive.incrementAndGet();
    try {
      Path cached = cached(video, format, fragment);
      if (cached != null) {
        LOG.debug("event=MEDIA_FRAGMENT_CACHE_HIT video={} format={} fragment={} priority={}",
            video, format, fragment, priority(high));
        return cached;
      }
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
        download(video, format, fragment, videoSource, tmpVideo, high, created, "video");
        Path tmpFinal = tmpVideo;
        if (audioSource != null) {
          Path tmpAudio = temporary(target);
          try {
            download(video, format, fragment, audioSource, tmpAudio, high, created, "audio");
            tmpFinal = remux(tmpVideo, tmpAudio, temporary(target), startSeconds);
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
    return open(video, format, fragment, source, null, 0d, high);
  }

  public InputStream open(String video, String format, String fragment, URI videoSource,
                          URI audioSource, boolean high) throws Exception {
    return open(video, format, fragment, videoSource, audioSource, 0d, high);
  }

  public InputStream open(String video, String format, String fragment, URI videoSource,
                          URI audioSource, double startSeconds, boolean high) throws Exception {
    // Acquire the lease before resolving/fetching the path. This closes the
    // eviction race where maintenance could delete a fragment between the
    // cache lookup and opening the stream.
    markActive(video, format, 0, 1);
    try {
      Path path = get(video, format, fragment, videoSource, audioSource, startSeconds, high);
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
  public void markComplete(String video, String format, int expectedFragments) {
    long completed = cachedFragments.countCompleted(video, format);
    if (expectedFragments > 0 && completed >= expectedFragments) {
      String now = now();
      cacheEntries.markComplete(video, now, now);
    }
  }

  public boolean isCached(String video, String format, String fragment) {
    return cached(video, format, fragment) != null;
  }

  private Path cached(String video, String format, String fragment) {
    CachedFragmentEntity entity = cachedFragments.findById(new CachedFragmentId(video, format, fragment)).orElse(null);
    if (entity == null || entity.getCompleted() == 0) return null;
    Path result = Path.of(entity.getPath());
    try {
      if (!Files.isRegularFile(result) || Files.size(result) == 0) {
        cachedFragments.deleteFragment(video, format, fragment);
        return null;
      }
    } catch (IOException e) {
      return null;
    }
    // Keep cache reads and the independent touch update short-lived.
    touch(video, format, fragment);
    return result;
  }

  private Path target(String video, String format, String fragment) {
    return paths.cacheRoot.resolve(safe(video)).resolve(safe(format)).resolve(safe(fragment) + ".part");
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

  private void download(String video, String format, String fragment, URI source, Path target,
                        boolean high, Inflight owner, String track) throws Exception {
    Exception failure = null;
    for (int attempt = 0; attempt < 4; attempt++) {
      try {
        downloadOnce(video, format, fragment, source, target, high, owner, track, attempt + 1);
        return;
      } catch (ExpiredSourceException e) {
        LOG.warn("event=MEDIA_FRAGMENT_SOURCE_EXPIRED video={} format={} fragment={} track={} priority={} attempt={} statusCode={}",
            video, format, fragment, track, priority(high), attempt + 1, e.statusCode());
        throw e;
      } catch (IOException | RetryableUpstreamException e) {
        failure = e;
        LOG.warn("event=MEDIA_FRAGMENT_DOWNLOAD_RETRY video={} format={} fragment={} track={} priority={} attempt={} reason={}",
            video, format, fragment, track, priority(high), attempt + 1, e.toString());
        Files.deleteIfExists(target);
        if (attempt == 3) break;
        Thread.sleep(250L << attempt);
      }
    }
    throw failure;
  }

  private void downloadOnce(String video, String format, String fragment, URI source, Path target,
                            boolean high, Inflight owner, String track, int attempt) throws Exception {
    if (source == null) throw new IllegalArgumentException("missing source URL");
    long downloadStarted = System.nanoTime();
    LOG.info("event=MEDIA_FRAGMENT_DOWNLOAD_STARTED video={} format={} fragment={} track={} priority={} attempt={} source={}",
        video, format, fragment, track, priority(high), attempt, sourceEndpoint(source));
    HttpRequest request = HttpRequest.newBuilder(source).GET().build();
    // Read in bounded chunks instead of BodyHandlers.ofFile so a low-priority
    // transfer can yield between reads when unrelated playback is active.
    HttpResponse<InputStream> response = externalHttp.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 410) {
      response.body().close();
      throw new ExpiredSourceException(response.statusCode());
    }
    if (response.statusCode() == 408 || response.statusCode() == 429 || response.statusCode() >= 500) {
      response.body().close();
      throw new RetryableUpstreamException(response.statusCode());
    }
    if (response.statusCode() < 200 || response.statusCode() > 299) {
      response.body().close();
      LOG.warn("event=MEDIA_FRAGMENT_DOWNLOAD_FAILED video={} format={} fragment={} track={} priority={} attempt={} statusCode={}",
          video, format, fragment, track, priority(high), attempt, response.statusCode());
      throw new IllegalStateException("upstream status " + response.statusCode());
    }
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
    LOG.info("event=MEDIA_FRAGMENT_DOWNLOAD_SUCCEEDED video={} format={} fragment={} track={} priority={} attempt={} statusCode={} bytes={} durationMs={}",
        video, format, fragment, track, priority(high), attempt, response.statusCode(), bytes,
        (System.nanoTime() - downloadStarted) / 1_000_000);
  }

  /** Wait while an unrelated interactive request is active. */
  private void awaitForegroundTurn() throws InterruptedException {
    while (interactive.get() > 0) Thread.sleep(10);
  }

  /** Apply the configured low-priority bandwidth limit while reading. */
  private void paceBackground(long bytes, long started, Inflight owner) throws InterruptedException {
    try {
      double mbps = Double.parseDouble(setting("background_download_max_mbps", "75"));
      if (mbps <= 0 || bytes <= 0) return;
      long minimum = (long) (bytes * 8_000_000_000d / (mbps * 1_000_000d));
      long wait = minimum - (System.nanoTime() - started);
      while (wait > 0 && !owner.promoted) {
        long slice = Math.min(wait, 10_000_000L);
        Thread.sleep(slice / 1_000_000, (int) (slice % 1_000_000));
        wait = minimum - (System.nanoTime() - started);
      }
    } catch (NumberFormatException ignored) {
      // Settings are validated at the API boundary; retain a safe fallback if
      // a legacy database contains an invalid value.
    }
  }

  private Path remux(Path video, Path audio, Path output, double expectedStart) throws Exception {
    String ffmpeg = setting("ffmpeg_path", "ffmpeg");
    LOG.info("event=MEDIA_FRAGMENT_REMUX_STARTED expectedStartSeconds={} ffmpeg={}", expectedStart, ffmpeg);
    Double videoStart = streamStart(video, ffmpeg);
    Double audioStart = streamStart(audio, ffmpeg);
    var command = new java.util.ArrayList<String>();
    command.add(ffmpeg);
    command.addAll(java.util.List.of("-hide_banner", "-loglevel", "error", "-copyts", "-i", video.toString()));
    double offset = audioOffset(videoStart, audioStart, expectedStart);
    if (Math.abs(offset) > 0.000_001d)
      command.addAll(java.util.List.of("-itsoffset", String.format(Locale.ROOT, "%.6f", offset)));
    command.addAll(java.util.List.of("-i", audio.toString(), "-map", "0:v:0", "-map", "1:a:0", "-c", "copy",
        "-muxpreload", "0", "-muxdelay", "0", "-f", "mpegts", "-mpegts_copyts", "1", "-y", output.toString()));
    Process process;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
    } catch (IOException e) {
      throw new IOException("ffmpeg executable is unavailable: " + ffmpeg
          + ". Install ffmpeg or set the ffmpeg_path setting to its absolute path.", e);
    }
    byte[] outputLog = process.getInputStream().readAllBytes();
    if (process.waitFor() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0)
      throw new IOException("stream-copy remux failed" + (outputLog.length == 0 ? "" : ": " + new String(outputLog)));
    LOG.info("event=MEDIA_FRAGMENT_REMUX_SUCCEEDED bytes={} expectedStartSeconds={}", Files.size(output), expectedStart);
    return output;
  }

  /**
   * DASH video fragments retain their presentation timestamp while audio
   * fragments commonly restart at zero. Preserve the video timeline and move
   * audio onto it so every cached HLS fragment has one shared clock.
   */
  private Double streamStart(Path media, String ffmpeg) throws Exception {
    String ffprobe = ffprobePath(ffmpeg);
    Process process;
    try {
      process = new ProcessBuilder(ffprobe, "-v", "error", "-show_entries", "stream=start_time",
          "-of", "default=noprint_wrappers=1:nokey=1", media.toString())
          .redirectErrorStream(true).start();
    } catch (IOException e) {
      return null;
    }
    String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    if (process.waitFor() != 0) return null;
    return parseStreamStart(result);
  }

  static Double parseStreamStart(String result) {
    if (result == null || result.isBlank()) return null;
    try {
      double start = Double.parseDouble(result.lines().findFirst().orElseThrow());
      return Double.isFinite(start) ? start : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static double audioOffset(Double videoStart, Double audioStart, double expectedStart) {
    double destination = videoStart == null ? Math.max(0d, expectedStart) : videoStart;
    return destination - (audioStart == null ? 0d : audioStart);
  }

  private static String ffprobePath(String ffmpeg) {
    Path path = Path.of(ffmpeg);
    Path name = path.getFileName();
    if (name != null && name.toString().equals("ffmpeg")) {
      Path parent = path.getParent();
      return parent == null ? "ffprobe" : parent.resolve("ffprobe").toString();
    }
    return "ffprobe";
  }

  private void upsert(String video, String format, String fragment, Path path) throws IOException {
    String now = now();
    synchronized (EVICTION_LOCK) {
      CacheEntryEntity entry = cacheEntries.findById(video).orElse(null);
      if (entry == null) {
        entry = new CacheEntryEntity(video, format, "PARTIAL", now, 0, 0, null);
      } else {
        entry.setFormatKey(format);
        if (!"COMPLETE".equals(entry.getStatus())) entry.setStatus("PARTIAL");
        entry.setLastAccessedAt(now);
      }
      cacheEntries.save(entry);
      CachedFragmentEntity cached = new CachedFragmentEntity(
          new CachedFragmentId(video, format, fragment), path.toString(), Files.size(path), 1, now);
      cachedFragments.save(cached);
    }
  }

  private void touch(String video, String format, String fragment) {
    String now = now();
    cachedFragments.touch(video, format, fragment, now);
    cacheEntries.touch(video, now);
  }

  private void markActive(String video, String format, int writers, int readers) {
    synchronized (EVICTION_LOCK) {
      String now = now();
      CacheEntryEntity entry = cacheEntries.findById(video).orElse(null);
      if (entry == null) {
        entry = new CacheEntryEntity(video, format, "PARTIAL", now,
            Math.max(readers, 0), Math.max(writers, 0), null);
      } else {
        entry.setFormatKey(format);
        entry.setActiveReaders(Math.max(0, entry.getActiveReaders() + readers));
        entry.setActiveWriters(Math.max(0, entry.getActiveWriters() + writers));
        entry.setLastAccessedAt(now);
      }
      cacheEntries.save(entry);
    }
  }

  private void markActiveQuietly(String video, String format, int writers, int readers) {
    try { markActive(video, format, writers, readers); } catch (RuntimeException ignored) { }
  }

  private void waitForInteractive() throws InterruptedException {
    awaitForegroundTurn();
  }

  private String setting(String key, String fallback) {
    String value = settingsService.value(key);
    return value == null ? fallback : value;
  }

  private static String now() {
    return java.time.Instant.now().toString();
  }

  private static String priority(boolean high) {
    return high ? "interactive" : "background";
  }

  private static String sourceEndpoint(URI source) {
    if (source == null) return "<missing>";
    String host = source.getHost();
    String path = source.getRawPath();
    if (host == null || host.isBlank()) return source.getScheme() + ":" + (path == null ? "" : path);
    String port = source.getPort() < 0 ? "" : ":" + source.getPort();
    return source.getScheme() + "://" + host + port + (path == null ? "" : path);
  }

  private static String key(String video, String format, String fragment) { return video + "/" + format + "/" + fragment; }
  static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
  public static class ExpiredSourceException extends Exception {
    private final int statusCode;

    ExpiredSourceException(int statusCode) {
      this.statusCode = statusCode;
    }

    int statusCode() { return statusCode; }
  }
  private static final class RetryableUpstreamException extends Exception {
    RetryableUpstreamException(int status) { super("temporary upstream status " + status); }
  }
}
