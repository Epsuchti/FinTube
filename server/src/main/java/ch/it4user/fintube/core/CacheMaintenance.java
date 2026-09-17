package ch.it4user.fintube.core;

import ch.it4user.fintube.media.FragmentManager;
import ch.it4user.fintube.persistence.entities.CacheEntryEntity;
import ch.it4user.fintube.persistence.repositories.CacheEntryRepository;
import ch.it4user.fintube.persistence.entities.CachedFragmentEntity;
import ch.it4user.fintube.persistence.repositories.CachedFragmentRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/** Global retention and minimum-free-space maintenance for the shared cache. */
@Component
class CacheMaintenance {
  final ApplicationPaths paths;
  final SettingsService settingsService;
  final CacheEntryRepository cacheEntries;
  final CachedFragmentRepository cachedFragments;
  private final AtomicLong nextCleanupAt = new AtomicLong(0L);

  CacheMaintenance(ApplicationPaths paths, SettingsService settingsService,
                   CacheEntryRepository cacheEntries, CachedFragmentRepository cachedFragments) {
    this.paths = paths;
    this.settingsService = settingsService;
    this.cacheEntries = cacheEntries;
    this.cachedFragments = cachedFragments;
  }

  @PostConstruct
  void recoverTemporaryFiles() {
    try (Stream<Path> files = Files.walk(paths.cacheRoot)) {
      files.filter(p -> p.getFileName().toString().contains(".tmp-")).forEach(p -> {
        try { Files.deleteIfExists(p); } catch (Exception ignored) { }
      });
    } catch (Exception ignored) { }
  }

  /**
   * Wake frequently enough to notice an administrator's new interval without
   * requiring a process restart. The persisted interval controls whether work
   * runs; the one-minute scheduler tick is only a lightweight gate.
   */
  @Scheduled(fixedDelay = 60_000)
  void cleanup() {
    try {
      long now = System.currentTimeMillis();
      long scheduled = nextCleanupAt.get();
      if (scheduled > now) return;
      int interval = Integer.parseInt(setting("cache_cleanup_interval_minutes", "360"));
      if (interval < 1) interval = 360;
      // Reserve the next run before doing any I/O so overlapping scheduler
      // invocations cannot perform duplicate eviction work.
      nextCleanupAt.set(now + interval * 60_000L);
      int days = Integer.parseInt(setting("cache_retention_days", "30"));
      evict(EvictionMode.RETENTION, Instant.now().minus(Duration.ofDays(days)).toString(), Long.MAX_VALUE);
      long minimum = Long.parseLong(setting("cache_min_free_gb", "20")) * 1024 * 1024 * 1024L;
      long usable = Files.getFileStore(paths.cacheRoot).getUsableSpace();
      if (usable < minimum) evict(EvictionMode.PRESSURE, null, minimum - usable);
    } catch (RuntimeException | java.io.IOException ignored) { }
  }

  /** Retention deletes aged data; pressure eviction removes oldest inactive fragments. */
  void evict(EvictionMode mode, String cutoff, long needed) throws java.io.IOException {
    long freed = 0;
    List<CachedFragmentEntity> candidates = mode == EvictionMode.RETENTION
        ? cachedFragments.findEvictableBefore(cutoff)
        : cachedFragments.findEvictableAll();
    for (CachedFragmentEntity candidate : candidates) {
      if (freed >= needed) break;
      String video = candidate.getVideoId();
      synchronized (FragmentManager.EVICTION_LOCK) {
        // Recheck active leases before unlinking. Lease acquisition uses the
        // same process lock, making this check-and-unlink atomic in this JVM.
        CacheEntryEntity entry = cacheEntries.findById(video).orElse(null);
        if (entry != null && (entry.getActiveReaders() > 0 || entry.getActiveWriters() > 0)) continue;
        Files.deleteIfExists(Path.of(candidate.getPath()));
        // Recheck active leases in the repository before removing the row.
        int deleted = cachedFragments.deleteIfInactive(video, candidate.getFormatKey(), candidate.getFragmentId());
        if (deleted > 0) freed += candidate.getSizeBytes();
      }
    }
  }

  private String setting(String key, String fallback) {
    String value = settingsService.value(key);
    return value == null ? fallback : value;
  }

  enum EvictionMode { RETENTION, PRESSURE }
}
