package ch.it4user.fintube.integration;

import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates the asynchronous Jellyfin scan/runtime updates caused by
 * generated per-user library files.  Ingestion is never made dependent on a
 * healthy Jellyfin instance: failures remain visible in admin status and are
 * retried for a short period after the scan request.
 */
@Service
public class JellyfinSyncService {
  private static final Logger LOG = LoggerFactory.getLogger(JellyfinSyncService.class);
  final JellyfinClient client;
  final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
    Thread t = new Thread(r, "fintube-jellyfin-sync");
    t.setDaemon(true);
    return t;
  });
  final AtomicBoolean refreshQueued = new AtomicBoolean();
  final AtomicInteger refreshBatchDepth = new AtomicInteger();
  final AtomicBoolean refreshPending = new AtomicBoolean();
  final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

  volatile String lastRefreshAt;
  volatile String lastRefreshMessage = "not requested";
  volatile boolean lastRefreshSuccess;
  volatile String lastRuntimeSyncAt;
  volatile String lastRuntimeSyncMessage = "not requested";
  volatile boolean lastRuntimeSyncSuccess;
  volatile long refreshCount;
  volatile long runtimeSuccessCount;
  volatile long runtimeFailureCount;

  public JellyfinSyncService(JellyfinClient client) { this.client = client; }

  private record Pending(String videoId, Path libraryPath, long durationSeconds, int attempt) {}

  public RefreshBatch beginRefreshBatch() {
    int depth = refreshBatchDepth.incrementAndGet();
    LOG.debug("event=JELLYFIN_REFRESH_BATCH_STARTED depth={}", depth);
    return new RefreshBatch();
  }

  public final class RefreshBatch implements AutoCloseable {
    private boolean closed;

    @Override
    public void close() {
      if (closed) return;
      closed = true;
      int depth = refreshBatchDepth.decrementAndGet();
      if (depth < 0) {
        refreshBatchDepth.set(0);
        throw new IllegalStateException("Jellyfin refresh batch closed without being opened");
      }
      boolean pendingRefresh = depth == 0 && refreshPending.compareAndSet(true, false);
      LOG.debug("event=JELLYFIN_REFRESH_BATCH_FINISHED depth={} pendingRefresh={}", depth, pendingRefresh);
      if (pendingRefresh) queueRefresh();
    }
  }

  /** Called after a user's .strm/.nfo/artwork files have been atomically created. */
  public void afterLibraryGeneration(String videoId, Path libraryPath, long durationSeconds) {
    requestLibraryRefresh();
    JellyfinClient.Configuration c = client.configuration();
    if (!c.enabled() || !c.configured() || !c.runtimeSync()) return;
    pending.put(pendingKey(videoId, libraryPath), new Pending(videoId, libraryPath, durationSeconds, 0));
    scheduleRuntime(videoId, libraryPath, durationSeconds, 0, 2);
  }

  public void afterLibraryDeletion() {
    requestLibraryRefresh();
  }

  private void requestLibraryRefresh() {
    JellyfinClient.Configuration c = client.configuration();
    if (!c.enabled() || !c.configured()) return;
    if (c.autoRefresh()) {
      if (refreshBatchDepth.get() > 0) refreshPending.set(true);
      else queueRefresh();
    }
  }

  /** Queue one coalesced scan for all files generated in the current batch. */
  private void queueRefresh() {
    JellyfinClient.Configuration c = client.configuration();
    if (!c.enabled() || !c.configured() || !c.autoRefresh() || !refreshQueued.compareAndSet(false, true)) return;
    LOG.info("event=JELLYFIN_LIBRARY_REFRESH_SCHEDULED delayMs=250");
    executor.schedule(() -> {
      try {
        JellyfinClient.Operation result = client.refreshLibraries();
        lastRefreshAt = Instant.now().toString();
        lastRefreshSuccess = result.success();
        lastRefreshMessage = result.message();
        refreshCount++;
        LOG.info("event=JELLYFIN_LIBRARY_REFRESH_COMPLETED success={} statusCode={} message={} count={}",
            result.success(), result.statusCode(), result.message(), refreshCount);
      } finally {
        refreshQueued.set(false);
      }
    }, 250, TimeUnit.MILLISECONDS);
  }

  private void scheduleRuntime(String videoId, Path libraryPath, long durationSeconds,
                               int attempt, long delaySeconds) {
    String key = pendingKey(videoId, libraryPath);
    executor.schedule(() -> syncRuntime(key, videoId, libraryPath, durationSeconds, attempt),
        Math.max(0, delaySeconds), TimeUnit.SECONDS);
  }

  private void syncRuntime(String key, String videoId, Path libraryPath, long durationSeconds, int attempt) {
    JellyfinClient.Configuration c = client.configuration();
    if (!c.enabled() || !c.configured() || !c.runtimeSync()) {
      pending.remove(key);
      return;
    }
    var item = client.findItem(videoId, libraryPath);
    if (item.isPresent()) {
      JellyfinClient.Operation result = client.updateRuntime(item.get().id(), durationSeconds);
      lastRuntimeSyncAt = Instant.now().toString();
      lastRuntimeSyncSuccess = result.success();
      lastRuntimeSyncMessage = result.message();
      if (result.success()) {
        runtimeSuccessCount++;
        pending.remove(key);
        return;
      }
    } else {
      lastRuntimeSyncAt = Instant.now().toString();
      lastRuntimeSyncSuccess = false;
      lastRuntimeSyncMessage = "item not found yet; Jellyfin may still be scanning";
    }
    runtimeFailureCount++;
    // Jellyfin scans are asynchronous. Retry after progressively longer delays,
    // but do not keep an unavailable server busy forever.
    if (attempt < 3) {
      pending.put(key, new Pending(videoId, libraryPath, durationSeconds, attempt + 1));
      scheduleRuntime(videoId, libraryPath, durationSeconds, attempt + 1,
          switch (attempt) { case 0 -> 5; case 1 -> 15; default -> 45; });
    } else {
      pending.remove(key);
    }
  }

  /** Synchronous admin operation; useful for recovering from a missed scan. */
  public Map<String, Object> refreshNow() {
    JellyfinClient.Operation result = client.refreshLibraries();
    lastRefreshAt = Instant.now().toString();
    lastRefreshSuccess = result.success();
    lastRefreshMessage = result.message();
    refreshCount++;
    return Map.of("success", result.success(), "statusCode", result.statusCode(),
        "message", result.message());
  }

  /** Live validation plus recent asynchronous synchronization state. */
  public Map<String, Object> adminStatus() {
    JellyfinClient.Status status = client.status();
    return Map.ofEntries(
        Map.entry("enabled", status.enabled()),
        Map.entry("configured", status.configured()),
        Map.entry("reachable", status.reachable()),
        Map.entry("apiKeyConfigured", status.apiKeyConfigured()),
        Map.entry("statusCode", status.statusCode()),
        Map.entry("baseUrl", status.baseUrl()),
        Map.entry("serverName", status.serverName()),
        Map.entry("version", status.version()),
        Map.entry("message", status.message()),
        Map.entry("refreshCount", refreshCount),
        Map.entry("lastRefreshAt", value(lastRefreshAt)),
        Map.entry("lastRefreshSuccess", lastRefreshSuccess),
        Map.entry("lastRefreshMessage", lastRefreshMessage),
        Map.entry("runtimeSuccessCount", runtimeSuccessCount),
        Map.entry("runtimeFailureCount", runtimeFailureCount),
        Map.entry("pendingRuntimeSyncs", pending.size()),
        Map.entry("lastRuntimeSyncAt", value(lastRuntimeSyncAt)),
        Map.entry("lastRuntimeSyncSuccess", lastRuntimeSyncSuccess),
        Map.entry("lastRuntimeSyncMessage", lastRuntimeSyncMessage));
  }

  private static String value(String x) { return x == null ? "" : x; }

  private static String pendingKey(String videoId, Path libraryPath) {
    return videoId + "\u0000" + (libraryPath == null ? "" : libraryPath.toAbsolutePath().normalize());
  }

  @PreDestroy
  void shutdown() { executor.shutdownNow(); }
}
