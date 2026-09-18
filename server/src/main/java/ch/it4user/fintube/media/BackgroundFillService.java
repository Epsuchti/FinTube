package ch.it4user.fintube.media;

import ch.it4user.fintube.persistence.entities.JobEntity;
import ch.it4user.fintube.persistence.repositories.JobRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Low-priority, restartable completion of the same source/cache used by playback. */
@Service
public class BackgroundFillService {
  final JobRepository jobs;
  final MediaSourceService sources;
  final FragmentManager fragments;
  final ExecutorService workers = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "fintube-cache-filler"); t.setDaemon(true); return t;
  });
  final ConcurrentHashMap<String, String> queued = new ConcurrentHashMap<>();
  final ConcurrentHashMap<String, Future<?>> running = new ConcurrentHashMap<>();

  public BackgroundFillService(JobRepository j, MediaSourceService s, FragmentManager f) {
    jobs = j; sources = s; fragments = f;
  }

  /** Requeue interrupted jobs after process restart. */
  @PostConstruct
  void recover() {
    try {
      for (JobEntity job : jobs.findByTypeAndStatusIn("BACKGROUND_FILL", List.of("QUEUED", "RUNNING"))) {
        submit(job.getVideoId(), job.getId());
      }
    } catch (RuntimeException ignored) { }
  }

  /** Existing playback API: enqueue one completion job, deduplicated by video. */
  public void enqueue(String video) { enqueueJob(video, null); }

  /** Queue and return a persistent job identifier for admin/status APIs. */
  public String enqueueJob(String video, String requestedFragment) {
    String existing = queued.get(video);
    if (existing != null) return existing;
    String id = UUID.randomUUID().toString();
    String now = now();
    try {
      jobs.save(new JobEntity(id, video, "QUEUED", 10, now, null, "BACKGROUND_FILL",
          requestedFragment, 0, 0, 0, null, now, null));
    } catch (RuntimeException e) {
      return null;
    }
    submit(video, id);
    return id;
  }

  /** Cancel a queued or running background job. */
  public boolean cancel(String id) {
    boolean changed;
    try {
      changed = jobs.cancelBackgroundFill(id, now()) > 0;
    } catch (RuntimeException ignored) {
      changed = false;
    }
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
            fragments.get(video, source.format(), part.id(), part.url(), part.audioUrl(), part.startSeconds(), false);
          } catch (FragmentManager.ExpiredSourceException expired) {
            source = sources.refresh(video);
            MediaSourceService.Fragment fresh = source.fragments().stream().filter(x -> x.id().equals(part.id())).findFirst().orElseThrow();
            fragments.get(video, source.format(), fresh.id(), fresh.url(), fresh.audioUrl(), fresh.startSeconds(), false);
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

  private boolean cancelled(String id) {
    return jobs.findById(id)
        .map(job -> job.getCancelRequested() != 0 || "CANCELLED".equals(job.getStatus()))
        .orElse(true);
  }

  private void update(String id, String status, String error, int completed, int total) {
    String now = now();
    String completedAt = status.equals("COMPLETED") || status.equals("FAILED") || status.equals("CANCELLED")
        ? now : null;
    jobs.updateProgress(id, status, error, completed, total, now, completedAt);
  }
  private void updateQuietly(String id, String status, String error, int completed, int total) { try { update(id, status, error, completed, total); } catch (RuntimeException ignored) { } }

  private static String now() {
    return Instant.now().toString();
  }

}
