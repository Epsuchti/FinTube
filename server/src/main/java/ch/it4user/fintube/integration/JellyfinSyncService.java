package ch.it4user.fintube.integration;

import ch.it4user.fintube.core.ApplicationPaths;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
  final ApplicationPaths paths;
  final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
    Thread t = new Thread(r, "fintube-jellyfin-sync");
    t.setDaemon(true);
    return t;
  });
  final AtomicBoolean refreshQueued = new AtomicBoolean();
  final AtomicInteger refreshBatchDepth = new AtomicInteger();
  final AtomicBoolean refreshPending = new AtomicBoolean();
  final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
  final ConcurrentHashMap<String, PendingSort> pendingSorts = new ConcurrentHashMap<>();
  final AtomicBoolean sortingReconciliationQueued = new AtomicBoolean();

  volatile String lastRefreshAt;
  volatile String lastRefreshMessage = "not requested";
  volatile boolean lastRefreshSuccess;
  volatile String lastRuntimeSyncAt;
  volatile String lastRuntimeSyncMessage = "not requested";
  volatile boolean lastRuntimeSyncSuccess;
  volatile String lastSortingSyncAt;
  volatile String lastSortingSyncMessage = "not requested";
  volatile boolean lastSortingSyncSuccess;
  volatile long refreshCount;
  volatile long runtimeSuccessCount;
  volatile long runtimeFailureCount;
  volatile long sortingChangedCount;
  volatile long sortingFailureCount;

  public JellyfinSyncService(JellyfinClient client, ApplicationPaths paths) {
    this.client = client;
    this.paths = paths;
  }

  private record Pending(String videoId, Path libraryPath, long durationSeconds, int attempt) {}
  private record PendingSort(Path folder, String sortBy, String sortOrder, int attempt) {}

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
    syncPlaybackRuntime(videoId, libraryPath, durationSeconds);
  }

  /** Match the chosen playback timeline without triggering a library scan on every play. */
  public void syncPlaybackRuntime(String videoId, Path libraryPath, long durationSeconds) {
    JellyfinClient.Configuration c = client.configuration();
    if (!c.enabled() || !c.configured() || !c.runtimeSync()) return;
    pending.put(pendingKey(videoId, libraryPath), new Pending(videoId, libraryPath, durationSeconds, 0));
    scheduleRuntime(videoId, libraryPath, durationSeconds, 0, 2);
  }

  /** Called when FinTube has materialized a channel or playlist collection folder. */
  public void afterManagedFolderGeneration(Path userRoot, Path folder) {
    try {
      if (userRoot == null || folder == null) return;
      Path root = userRoot.toAbsolutePath().normalize();
      Path child = folder.toAbsolutePath().normalize();
      if (!root.startsWith(paths.usersRoot.toAbsolutePath().normalize()) || !child.startsWith(root)
          || child.equals(root)) return;
      queueSort(root, "SortName", "Ascending", 0, 2);
      queueSort(child, "PremiereDate", "Descending", 0, 2);
    } catch (Exception e) {
      LOG.warn("event=JELLYFIN_SORT_QUEUE_FAILED reason={}", safeMessage(e));
    }
  }

  /** Reconcile all existing FinTube roots and their channel/collection folders asynchronously. */
  public void reconcileSorting() {
    JellyfinClient.Configuration c = client.configuration();
    if (!c.enabled() || !c.configured() || !sortingReconciliationQueued.compareAndSet(false, true)) return;
    try {
      executor.execute(() -> {
        try {
          for (FolderSort target : managedFolderSorts()) {
            applySort(target.folder(), target.sortBy(), target.sortOrder(), false);
          }
        } catch (Exception e) {
          LOG.warn("event=JELLYFIN_SORT_RECONCILIATION_FAILED reason={}", safeMessage(e));
        } finally {
          sortingReconciliationQueued.set(false);
        }
      });
    } catch (Exception e) {
      sortingReconciliationQueued.set(false);
      LOG.warn("event=JELLYFIN_SORT_RECONCILIATION_QUEUE_FAILED reason={}", safeMessage(e));
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  void reconcileSortingOnStartup() {
    try {
      executor.schedule(this::reconcileSorting, 2, TimeUnit.SECONDS);
    } catch (Exception e) {
      LOG.warn("event=JELLYFIN_SORT_STARTUP_QUEUE_FAILED reason={}", safeMessage(e));
    }
  }

  private record FolderSort(Path folder, String sortBy, String sortOrder) {}

  List<FolderSort> managedFolderSorts() {
    List<FolderSort> result = new ArrayList<>();
    Path managedRoot = paths.usersRoot.toAbsolutePath().normalize();
    try (var users = Files.list(managedRoot)) {
      for (Path userRoot : users.filter(Files::isDirectory).toList()) {
        result.add(new FolderSort(userRoot, "SortName", "Ascending"));
        try (var folders = Files.list(userRoot)) {
          folders.filter(Files::isDirectory)
              .forEach(folder -> result.add(new FolderSort(folder, "PremiereDate", "Descending")));
        } catch (Exception e) {
          LOG.warn("event=JELLYFIN_SORT_USER_SCAN_FAILED path={} reason={}", userRoot, safeMessage(e));
        }
      }
    } catch (Exception e) {
      LOG.warn("event=JELLYFIN_SORT_SCAN_FAILED reason={}", safeMessage(e));
    }
    return result;
  }

  private void queueSort(Path folder, String sortBy, String sortOrder, int attempt, long delaySeconds) {
    JellyfinClient.Configuration c = client.configuration();
    if (!c.enabled() || !c.configured()) return;
    String key = folder.toAbsolutePath().normalize().toString();
    PendingSort next = new PendingSort(folder, sortBy, sortOrder, attempt);
    if (attempt == 0 && pendingSorts.putIfAbsent(key, next) != null) return;
    pendingSorts.put(key, next);
    executor.schedule(() -> applySort(folder, sortBy, sortOrder, true), Math.max(0, delaySeconds), TimeUnit.SECONDS);
  }

  private void applySort(Path folder, String sortBy, String sortOrder, boolean retryIfMissing) {
    String key = folder.toAbsolutePath().normalize().toString();
    PendingSort pending = pendingSorts.get(key);
    int attempt = pending == null ? 0 : pending.attempt();
    try {
      JellyfinClient.Configuration c = client.configuration();
      if (!c.enabled() || !c.configured()) {
        pendingSorts.remove(key);
        return;
      }
      JellyfinClient.SortingResult result = client.ensureFolderSorting(folder, paths.usersRoot, sortBy, sortOrder);
      lastSortingSyncAt = Instant.now().toString();
      lastSortingSyncSuccess = result.itemFound() && result.failed() == 0;
      lastSortingSyncMessage = result.message();
      sortingChangedCount += result.changed();
      sortingFailureCount += result.failed();
      if (result.itemFound()) {
        pendingSorts.remove(key);
        LOG.debug("event=JELLYFIN_SORT_RECONCILED path={} users={} changed={} failed={}",
            folder, result.users(), result.changed(), result.failed());
        return;
      }
      if (retryIfMissing && attempt < 3) {
        queueSort(folder, sortBy, sortOrder, attempt + 1,
            switch (attempt) { case 0 -> 5; case 1 -> 15; default -> 45; });
      } else {
        pendingSorts.remove(key);
        LOG.debug("event=JELLYFIN_SORT_DEFERRED path={} message={}", folder, result.message());
      }
    } catch (Exception e) {
      pendingSorts.remove(key);
      LOG.warn("event=JELLYFIN_SORT_FAILED path={} reason={}", folder, safeMessage(e));
    }
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
        Map.entry("pendingSortingSyncs", pendingSorts.size()),
        Map.entry("sortingChangedCount", sortingChangedCount),
        Map.entry("sortingFailureCount", sortingFailureCount),
        Map.entry("lastSortingSyncAt", value(lastSortingSyncAt)),
        Map.entry("lastSortingSyncSuccess", lastSortingSyncSuccess),
        Map.entry("lastSortingSyncMessage", lastSortingSyncMessage),
        Map.entry("lastRuntimeSyncAt", value(lastRuntimeSyncAt)),
        Map.entry("lastRuntimeSyncSuccess", lastRuntimeSyncSuccess),
        Map.entry("lastRuntimeSyncMessage", lastRuntimeSyncMessage));
  }

  private static String value(String x) { return x == null ? "" : x; }

  private static String pendingKey(String videoId, Path libraryPath) {
    return videoId + "\u0000" + (libraryPath == null ? "" : libraryPath.toAbsolutePath().normalize());
  }

  private static String safeMessage(Exception e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  @PreDestroy
  void shutdown() { executor.shutdownNow(); }
}
