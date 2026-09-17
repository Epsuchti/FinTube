package ch.it4user.fintube.core;

import ch.it4user.fintube.media.FragmentManager;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/** Global retention and minimum-free-space maintenance for the shared cache. */
@Component
class CacheMaintenance {
  final Database db;
  private final AtomicLong nextCleanupAt = new AtomicLong(0L);
  CacheMaintenance(Database d) { db = d; }

  @PostConstruct
  void recoverTemporaryFiles() {
    try (Stream<Path> files = Files.walk(db.cacheRoot)) {
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
      int interval = Integer.parseInt(db.settings(false)
          .getOrDefault("cache_cleanup_interval_minutes", "360"));
      if (interval < 1) interval = 360;
      // Reserve the next run before doing any I/O so overlapping scheduler
      // invocations cannot perform duplicate eviction work.
      nextCleanupAt.set(now + interval * 60_000L);
      int days = Integer.parseInt(db.settings(false).getOrDefault("cache_retention_days", "30"));
      evict("f.last_accessed_at<?", Instant.now().minus(Duration.ofDays(days)).toString(), Long.MAX_VALUE);
      long minimum = Long.parseLong(db.settings(false).getOrDefault("cache_min_free_gb", "20")) * 1024 * 1024 * 1024L;
      long usable = Files.getFileStore(db.cacheRoot).getUsableSpace();
      if (usable < minimum) evict("1=1", null, minimum - usable);
    } catch (Exception ignored) { }
  }

  /** Retention deletes aged data; pressure eviction removes oldest inactive fragments. */
  void evict(String predicate, String cutoff, long needed) throws Exception {
    long freed = 0;
    try (Connection c = db.open(); PreparedStatement q = c.prepareStatement(
        "SELECT f.video_id,f.format_key,f.fragment_id,f.path,f.size_bytes FROM cached_fragments f WHERE " + predicate +
            " AND NOT EXISTS(SELECT 1 FROM cache_entries e WHERE e.video_id=f.video_id AND (e.active_readers>0 OR e.active_writers>0)) ORDER BY f.last_accessed_at ASC")) {
      if (cutoff != null) q.setString(1, cutoff);
      try (ResultSet r = q.executeQuery()) {
        while (r.next() && freed < needed) {
          String video = r.getString(1), format = r.getString(2), fragment = r.getString(3);
          Path file = Path.of(r.getString(4));
          long size = r.getLong(5);
          synchronized (FragmentManager.EVICTION_LOCK) {
            try (PreparedStatement active = c.prepareStatement("SELECT active_readers,active_writers FROM cache_entries WHERE video_id=?")) {
              active.setString(1, video);
              try (ResultSet a = active.executeQuery()) {
                if (a.next() && (a.getInt(1) > 0 || a.getInt(2) > 0)) continue;
              }
            }
            Files.deleteIfExists(file);
            // Recheck active leases before removing the row. Lease acquisition
            // uses the same process lock, so this check-and-unlink is atomic
            // with respect to new readers/writers in this JVM.
            try (PreparedStatement d = c.prepareStatement(
                "DELETE FROM cached_fragments WHERE video_id=? AND format_key=? AND fragment_id=? " +
                    "AND NOT EXISTS(SELECT 1 FROM cache_entries e WHERE e.video_id=? AND (e.active_readers>0 OR e.active_writers>0))")) {
              d.setString(1, video); d.setString(2, format); d.setString(3, fragment); d.setString(4, video);
              d.executeUpdate();
            }
          }
          freed += size;
        }
      }
    }
  }
}
