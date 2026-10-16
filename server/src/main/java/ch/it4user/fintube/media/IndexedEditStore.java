package ch.it4user.fintube.media;

import ch.it4user.fintube.core.ApplicationPaths;
import ch.it4user.fintube.core.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps SponsorBlock edit plans stable for the lifetime of a playback session:
 * playlists and segments reference a plan by id, so a SponsorBlock update or a
 * restart never mixes two timelines in one session.
 */
@Service
public class IndexedEditStore {
  private static final Logger LOG = LoggerFactory.getLogger(IndexedEditStore.class);
  private final ApplicationPaths paths;
  private final SettingsService settings;
  private final ObjectMapper json = new ObjectMapper();
  private final ConcurrentHashMap<String, IndexedEdits.Plan> memory = new ConcurrentHashMap<>();

  public IndexedEditStore(ApplicationPaths paths, SettingsService settings) {
    this.paths = paths;
    this.settings = settings;
  }

  public void save(String video, IndexedEdits.Plan plan) {
    if (memory.size() > 2_000) memory.clear();
    memory.put(video + "/" + plan.id(), plan);
    try {
      Path file = file(video, plan.id());
      if (Files.isRegularFile(file)) { Files.setLastModifiedTime(file, FileTime.from(Instant.now())); return; }
      Files.createDirectories(file.getParent());
      Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
      json.writeValue(temporary.toFile(), plan);
      Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception failure) {
      LOG.warn("event=SPONSOR_INDEXED_PLAN_SAVE_FAILED video={} plan={} reason={}", video, plan.id(), failure.toString());
    }
  }

  public IndexedEdits.Plan find(String video, String id) {
    if (id == null || !id.matches("[a-f0-9]{24}")) return null;
    IndexedEdits.Plan cached = memory.get(video + "/" + id);
    if (cached != null) return cached;
    try {
      Path file = file(video, id);
      if (!Files.isRegularFile(file)) return null;
      IndexedEdits.Plan plan = json.readValue(file.toFile(), IndexedEdits.Plan.class);
      memory.put(video + "/" + id, plan);
      return plan;
    } catch (Exception failure) {
      LOG.warn("event=SPONSOR_INDEXED_PLAN_READ_FAILED video={} plan={} reason={}", video, id, failure.toString());
      return null;
    }
  }

  private Path file(String video, String id) throws Exception {
    String folder = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(video.getBytes(StandardCharsets.UTF_8)));
    return paths.root.resolve("sponsor-plans").resolve(folder).resolve(id + ".json");
  }

  @Scheduled(fixedDelay = 21_600_000, initialDelay = 3_600_000)
  void cleanup() {
    Path root = paths.root.resolve("sponsor-plans");
    if (!Files.isDirectory(root)) return;
    try {
      String configured = settings.value("cache_retention_days");
      Instant cutoff = Instant.now().minus(Duration.ofDays(Math.max(1, Integer.parseInt(configured == null ? "30" : configured))));
      try (var files = Files.walk(root)) {
        for (Path file : files.filter(Files::isRegularFile).toList())
          if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) Files.deleteIfExists(file);
      }
    } catch (Exception failure) {
      LOG.warn("event=SPONSOR_INDEXED_PLAN_CLEANUP_FAILED reason={}", failure.toString());
    }
  }
}
