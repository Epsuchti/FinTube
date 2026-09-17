package ch.it4user.fintube.media;

import ch.it4user.fintube.core.Database;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Low-priority, restartable completion of the same source/cache used by playback. */
@Service
public class BackgroundFillService {
  final Database db;
  final MediaSourceService sources;
  final FragmentManager fragments;
  final ExecutorService workers = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "fintube-cache-filler"); t.setDaemon(true); return t;
  });
  final ConcurrentHashMap<String, String> queued = new ConcurrentHashMap<>();
  final ConcurrentHashMap<String, Future<?>> running = new ConcurrentHashMap<>();

  public BackgroundFillService(Database d, MediaSourceService s, FragmentManager f) {
    db = d; sources = s; fragments = f;
  }

  /** Requeue interrupted jobs after process restart. */
  @PostConstruct
  void recover() {
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "SELECT id,video_id FROM jobs WHERE type='BACKGROUND_FILL' AND status IN ('QUEUED','RUNNING')"); ResultSet r = p.executeQuery()) {
      while (r.next()) submit(r.getString(2), r.getString(1));
    } catch (Exception ignored) { }
  }

  /** Existing playback API: enqueue one completion job, deduplicated by video. */
  public void enqueue(String video) { enqueueJob(video, null); }

  /** Queue and return a persistent job identifier for admin/status APIs. */
  public String enqueueJob(String video, String requestedFragment) {
    String existing = queued.get(video);
    if (existing != null) return existing;
    String id = UUID.randomUUID().toString();
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "INSERT INTO jobs(id,video_id,status,priority,created_at,error,type,requested_fragment,updated_at) VALUES(?,?, 'QUEUED',?,?,?,?,?,?)")) {
      p.setString(1, id); p.setString(2, video); p.setInt(3, 10); p.setString(4, Database.now()); p.setNull(5, java.sql.Types.VARCHAR);
      p.setString(6, "BACKGROUND_FILL"); p.setString(7, requestedFragment); p.setString(8, Database.now()); p.executeUpdate();
    } catch (Exception e) {
      return null;
    }
    submit(video, id);
    return id;
  }

  /** Cancel a queued or running background job. */
  public boolean cancel(String id) {
    boolean changed = false;
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "UPDATE jobs SET cancel_requested=1,status='CANCELLED',updated_at=?,completed_at=? WHERE id=? AND type='BACKGROUND_FILL' AND status IN ('QUEUED','RUNNING')")) {
      p.setString(1, Database.now()); p.setString(2, Database.now()); p.setString(3, id); changed = p.executeUpdate() > 0;
    } catch (Exception ignored) { }
    Future<?> future = running.get(id);
    if (future != null) future.cancel(true);
    return changed;
  }

  private void submit(String video, String id) {
    if (queued.putIfAbsent(video, id) != null) return;
    Future<?> future = workers.submit(() -> run(video, id));
    running.put(id, future);
  }

  private void run(String video, String id) {
    try {
      update(id, "RUNNING", null, 0, 0);
      MediaSourceService.Source source = sources.source(video);
      update(id, "RUNNING", null, 0, source.fragments().size());
      int completed = 0;
      for (MediaSourceService.Fragment part : source.fragments()) {
        if (cancelled(id)) return;
        if (!fragments.isCached(video, source.format(), part.id())) {
          try {
            fragments.get(video, source.format(), part.id(), part.url(), part.audioUrl(), false);
          } catch (FragmentManager.ExpiredSourceException expired) {
            source = sources.refresh(video);
            MediaSourceService.Fragment fresh = source.fragments().stream().filter(x -> x.id().equals(part.id())).findFirst().orElseThrow();
            fragments.get(video, source.format(), fresh.id(), fresh.url(), fresh.audioUrl(), false);
          }
        }
        completed++;
        update(id, "RUNNING", null, completed, source.fragments().size());
      }
      fragments.markComplete(video, source.format(), source.fragments().size());
      update(id, "COMPLETED", null, completed, source.fragments().size());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      updateQuietly(id, "CANCELLED", "interrupted", 0, 0);
    } catch (Exception e) {
      boolean wasCancelled = false;
      try { wasCancelled = cancelled(id); } catch (Exception ignored) { }
      if (wasCancelled) updateQuietly(id, "CANCELLED", "cancelled", 0, 0);
      else updateQuietly(id, "FAILED", e.getClass().getSimpleName(), 0, 0);
    } finally {
      queued.remove(video, id); running.remove(id);
    }
  }

  private boolean cancelled(String id) throws Exception {
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement("SELECT cancel_requested,status FROM jobs WHERE id=?")) {
      p.setString(1, id);
      try (ResultSet r = p.executeQuery()) { return !r.next() || r.getInt(1) != 0 || "CANCELLED".equals(r.getString(2)); }
    }
  }

  private void update(String id, String status, String error, int completed, int total) throws Exception {
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "UPDATE jobs SET status=?,error=?,completed_fragments=?,total_fragments=?,updated_at=?,started_at=COALESCE(started_at,?),completed_at=? WHERE id=?")) {
      String now = Database.now(); p.setString(1, status); p.setString(2, error); p.setInt(3, completed); p.setInt(4, total); p.setString(5, now); p.setString(6, now);
      p.setString(7, status.equals("COMPLETED") || status.equals("FAILED") || status.equals("CANCELLED") ? now : null); p.setString(8, id); p.executeUpdate();
    }
  }
  private void updateQuietly(String id, String status, String error, int completed, int total) { try { update(id, status, error, completed, total); } catch (Exception ignored) { } }

}
