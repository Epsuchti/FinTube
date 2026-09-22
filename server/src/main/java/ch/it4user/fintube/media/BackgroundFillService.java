package ch.it4user.fintube.media;

import ch.it4user.fintube.persistence.entities.JobEntity;
import ch.it4user.fintube.persistence.repositories.JobRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
  private static final Logger LOG = LoggerFactory.getLogger(BackgroundFillService.class);
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
      List<JobEntity> recovered = jobs.findByTypeAndStatusIn("BACKGROUND_FILL", List.of("QUEUED", "RUNNING"));
      LOG.info("event=BACKGROUND_FILL_RECOVERY_FOUND jobs={}", recovered.size());
      for (JobEntity job : recovered) {
        LOG.info("event=BACKGROUND_FILL_RECOVERY_ENQUEUED jobId={} video={} status={} completedFragments={} totalFragments={}",
            job.getId(), job.getVideoId(), job.getStatus(), job.getCompletedFragments(), job.getTotalFragments());
        submit(job.getVideoId(), job.getId());
      }
    } catch (RuntimeException failure) {
      LOG.warn("event=BACKGROUND_FILL_RECOVERY_FAILED reason={}", failure.toString(), failure);
    }
  }

  /** Existing playback API: enqueue one completion job, deduplicated by video. */
  public void enqueue(String video) { enqueue(video, "unspecified"); }

  public void enqueue(String video, String reason) { enqueueJob(video, null, reason); }

  /** Queue and return a persistent job identifier for admin/status APIs. */
  public String enqueueJob(String video, String requestedFragment) {
    return enqueueJob(video, requestedFragment, "api");
  }

  public String enqueueJob(String video, String requestedFragment, String reason) {
    String existing = queued.get(video);
    if (existing != null) {
      LOG.debug("event=BACKGROUND_FILL_DEDUPLICATED video={} jobId={} reason={} requestedFragment={}",
          video, existing, reason, requestedFragment);
      return existing;
    }
    String id = UUID.randomUUID().toString();
    String now = now();
    try {
      jobs.save(new JobEntity(id, video, "QUEUED", 10, now, null, "BACKGROUND_FILL",
          requestedFragment, 0, 0, 0, null, now, null));
    } catch (RuntimeException e) {
      LOG.warn("event=BACKGROUND_FILL_ENQUEUE_FAILED video={} jobId={} reason={} failure={}",
          video, id, reason, e.toString(), e);
      return null;
    }
    LOG.info("event=BACKGROUND_FILL_ENQUEUED video={} jobId={} reason={} requestedFragment={}",
        video, id, reason, requestedFragment);
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
    LOG.info("event=BACKGROUND_FILL_CANCEL_REQUESTED jobId={} changed={} running={}", id, changed, future != null);
    return changed;
  }

  private void submit(String video, String id) {
    if (queued.putIfAbsent(video, id) != null) return;
    Future<?> future = workers.submit(() -> run(video, id));
    running.put(id, future);
  }

  private void run(String video, String id) {
    try {
      LOG.info("event=BACKGROUND_FILL_STARTED video={} jobId={}", video, id);
      update(id, "RUNNING", null, 0, 0);
      MediaSourceService.Source source = sources.source(video);
      LOG.info("event=BACKGROUND_FILL_SOURCE_RESOLVED video={} jobId={} format={} fragments={} durationSeconds={} expiresAt={}",
          video, id, source.format(), source.fragments().size(), source.duration(), source.expiresAt());
      if (source.fmp4()) {
        fillFmp4(video, id, source);
        return;
      }
      update(id, "RUNNING", null, 0, source.fragments().size());
      int completed = 0;
      for (MediaSourceService.Fragment part : source.fragments()) {
        if (cancelled(id)) {
          LOG.info("event=BACKGROUND_FILL_CANCELLED video={} jobId={} completedFragments={} reason=cancel_requested",
              video, id, completed);
          return;
        }
        boolean cached = fragments.isCached(video, source.format(), part.id());
        LOG.debug("event=BACKGROUND_FILL_FRAGMENT_CHECKED video={} jobId={} format={} fragment={} cached={}",
            video, id, source.format(), part.id(), cached);
        if (!cached) {
          try {
            fragments.get(video, source.format(), part.id(), part.url(), part.audioUrl(), part.startSeconds(), false);
          } catch (FragmentManager.ExpiredSourceException expired) {
            LOG.warn("event=BACKGROUND_FILL_SOURCE_REFRESH_REQUIRED video={} jobId={} format={} fragment={} reason=upstream_source_expired",
                video, id, source.format(), part.id());
            source = sources.refresh(video, "background_fragment_source_expired");
            MediaSourceService.Fragment fresh = source.fragments().stream().filter(x -> x.id().equals(part.id())).findFirst().orElseThrow();
            fragments.get(video, source.format(), fresh.id(), fresh.url(), fresh.audioUrl(), fresh.startSeconds(), false);
          }
        }
        completed++;
        update(id, "RUNNING", null, completed, source.fragments().size());
      }
      fragments.markComplete(video, source.format(), source.fragments().size());
      update(id, "COMPLETED", null, completed, source.fragments().size());
      LOG.info("event=BACKGROUND_FILL_COMPLETED video={} jobId={} completedFragments={} totalFragments={} format={}",
          video, id, completed, source.fragments().size(), source.format());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      updateQuietly(id, "CANCELLED", "interrupted", 0, 0);
      LOG.info("event=BACKGROUND_FILL_CANCELLED video={} jobId={} reason=interrupted", video, id);
    } catch (Exception e) {
      boolean wasCancelled = false;
      try { wasCancelled = cancelled(id); } catch (Exception ignored) { }
      if (wasCancelled) {
        updateQuietly(id, "CANCELLED", "cancelled", 0, 0);
        LOG.info("event=BACKGROUND_FILL_CANCELLED video={} jobId={} reason=cancelled failure={}",
            video, id, e.toString());
      } else {
        updateQuietly(id, "FAILED", e.getClass().getSimpleName(), 0, 0);
        LOG.error("event=BACKGROUND_FILL_FAILED video={} jobId={} failure={}", video, id, e.toString(), e);
      }
    } finally {
      queued.remove(video, id); running.remove(id);
      LOG.debug("event=BACKGROUND_FILL_SLOT_RELEASED video={} jobId={}", video, id);
    }
  }

  private void fillFmp4(String video, String id, MediaSourceService.Source source) throws Exception {
    if (source.videoInit() == null) throw new IllegalStateException("fMP4 source has no initialization segment");
    try {
      fragments.get(video, source.format() + "-video", "init", source.videoInit(), false);
    } catch (FragmentManager.ExpiredSourceException expired) {
      source = refreshFmp4(video, source);
      fragments.get(video, source.format() + "-video", "init", source.videoInit(), false);
    }
    int completed = 0;
    update(id, "RUNNING", null, 0, source.fragments().size());
    while (completed < source.fragments().size()) {
      if (cancelled(id)) return;
      MediaSourceService.Fragment part = source.fragments().get(completed);
      try {
        cacheFmp4Pair(video, source.format(), completed, part);
      } catch (FragmentManager.ExpiredSourceException expired) {
        source = refreshFmp4(video, source);
        cacheFmp4Pair(video, source.format(), completed, source.fragments().get(completed));
      }
      completed++;
      update(id, "RUNNING", null, completed, source.fragments().size());
    }
    fragments.markComplete(video, source.format() + "-video", source.fragments().size() + 1);
    update(id, "COMPLETED", null, completed, source.fragments().size());
  }

  private void cacheFmp4Pair(String video, String format, int index, MediaSourceService.Fragment part) throws Exception {
    fragments.get(video, format + "-video", "video" + index, part.url(), false);
    if (part.audioUrl() == null) throw new IllegalStateException("fMP4 source has no audio segment");
    fragments.get(video, format + "-audio", "audio" + index, part.audioUrl(), false);
  }

  private MediaSourceService.Source refreshFmp4(String video, MediaSourceService.Source original) throws Exception {
    var fresh = sources.refresh(video, "background_fmp4_source_expired");
    if (!fresh.fmp4() || !fresh.format().equals(original.format())
        || fresh.fragments().size() != original.fragments().size())
      throw new IllegalStateException("fMP4 source changed during background fill");
    return fresh;
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
