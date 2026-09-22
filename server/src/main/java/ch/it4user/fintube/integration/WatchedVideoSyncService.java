package ch.it4user.fintube.integration;

import ch.it4user.fintube.core.ApplicationClock;
import ch.it4user.fintube.core.ApplicationPaths;
import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.media.ProxiedHttpClient;
import ch.it4user.fintube.persistence.entities.UserEntity;
import ch.it4user.fintube.persistence.entities.UserVideoEntity;
import ch.it4user.fintube.persistence.entities.VideoEntity;
import ch.it4user.fintube.persistence.entities.WatchedVideoEntity;
import ch.it4user.fintube.persistence.repositories.UserRepository;
import ch.it4user.fintube.persistence.repositories.UserVideoRepository;
import ch.it4user.fintube.persistence.repositories.VideoRepository;
import ch.it4user.fintube.persistence.repositories.WatchedVideoRepository;
import ch.it4user.fintube.persistence.repositories.YouTubeChannelRepository;
import ch.it4user.fintube.service.UserYouTubeApiKeyService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Reconciles Jellyfin play state into FinTube during ordinary subscription syncs. */
@Service
public class WatchedVideoSyncService {
  private static final Logger LOG = LoggerFactory.getLogger(WatchedVideoSyncService.class);
  private static final int MAX_YOUTUBE_MARKS_PER_SYNC = 50;
  private static final Duration YTDLP_TIMEOUT = Duration.ofMinutes(2);

  private final SettingsService settings;
  private final ApplicationPaths paths;
  private final JellyfinClient jellyfinClient;
  private final JellyfinSyncService jellyfinSync;
  private final UserVideoRepository userVideos;
  private final WatchedVideoRepository watchedVideos;
  private final UserRepository users;
  private final VideoRepository videos;
  private final YouTubeChannelRepository channels;
  private final ProxiedHttpClient externalHttp;
  private final UserYouTubeApiKeyService youtubeApiKeys;

  public WatchedVideoSyncService(SettingsService settings, ApplicationPaths paths,
                                 JellyfinClient jellyfinClient, JellyfinSyncService jellyfinSync,
                                 UserVideoRepository userVideos, WatchedVideoRepository watchedVideos,
                                 UserRepository users, VideoRepository videos,
                                 YouTubeChannelRepository channels, ProxiedHttpClient externalHttp,
                                 UserYouTubeApiKeyService youtubeApiKeys) {
    this.settings = settings;
    this.paths = paths;
    this.jellyfinClient = jellyfinClient;
    this.jellyfinSync = jellyfinSync;
    this.userVideos = userVideos;
    this.watchedVideos = watchedVideos;
    this.users = users;
    this.videos = videos;
    this.channels = channels;
    this.externalHttp = externalHttp;
    this.youtubeApiKeys = youtubeApiKeys;
  }

  /** Failures are deliberately isolated so watch-history outages never block ingestion. */
  public synchronized void reconcile() {
    Map<String, String> current = settings.values(false);
    if (enabled(current, "jellyfin_remove_watched")) removePlayedJellyfinItems(current);
    if (enabled(current, "youtube_mark_watched")) markPendingOnYouTube(current);
  }

  private void removePlayedJellyfinItems(Map<String, String> current) {
    JellyfinClient.PlayedItems played = jellyfinClient.playedItems(current.get("jellyfin_watched_user"));
    if (!played.success()) {
      LOG.warn("event=JELLYFIN_WATCHED_RECONCILE_SKIPPED reason={}", played.message());
      return;
    }
    List<UserVideoEntity> links = new ArrayList<>(userVideos.findAll());
    Map<Long, Path> roots = userRoots();
    int removed = 0;
    for (JellyfinClient.PlayedItem item : played.items()) {
      UserVideoEntity link = matchingLink(item, links, roots);
      if (link == null) continue;
      try {
        VideoEntity video = videos.findById(link.getId().getVideoId()).orElse(null);
        if (video == null) continue;
        watchedVideos.findById(link.getId()).orElseGet(() ->
            watchedVideos.save(new WatchedVideoEntity(link.getId(), video.getChannelId(),
                category(video), ApplicationClock.now())));
        Path root = roots.get(link.getId().getUserId());
        Path libraryPath = safeLibraryPath(link, root);
        if (libraryPath != null) {
          deleteTree(libraryPath, root);
          deleteIfEmpty(libraryPath.getParent(), root);
        }
        // A playlist can contain a video that is also linked from a channel.
        // The canonical user link has one path, so remove any additional
        // playlist materializations by their stable video-id directory name.
        removeOtherVideoFolders(root, link.getId().getVideoId());
        userVideos.delete(link);
        links.remove(link);
        resetChannelCursor(video.getChannelId());
        removed++;
        LOG.info("event=WATCHED_VIDEO_REMOVED userId={} video={} jellyfinItem={}",
            link.getId().getUserId(), link.getId().getVideoId(), item.id());
      } catch (Exception e) {
        LOG.warn("event=WATCHED_VIDEO_REMOVE_FAILED userId={} video={} reason={}",
            link.getId().getUserId(), link.getId().getVideoId(), safeMessage(e));
      }
    }
    if (removed > 0) jellyfinSync.afterLibraryDeletion();
  }

  private void resetChannelCursor(String channelId) {
    channels.findById(channelId).ifPresent(channel -> {
      channel.setLastSyncPublishedAt(null);
      channels.save(channel);
    });
  }

  private static int category(VideoEntity video) {
    if (video.getIsLiveStream() != 0) return 2;
    return video.getIsShort() != 0 ? 1 : 0;
  }

  private void markPendingOnYouTube(Map<String, String> current) {
    List<WatchedVideoEntity> pending = watchedVideos.findByYoutubeMarkedAtIsNullOrderByWatchedAtAsc(
        PageRequest.of(0, MAX_YOUTUBE_MARKS_PER_SYNC));
    if (pending.isEmpty()) return;
    Map<Long, List<WatchedVideoEntity>> byUser = pending.stream()
        .collect(java.util.stream.Collectors.groupingBy(value -> value.getId().getUserId(), LinkedHashMap::new, java.util.stream.Collectors.toList()));
    for (Map.Entry<Long, List<WatchedVideoEntity>> entry : byUser.entrySet()) {
      String cookieFile = youtubeApiKeys.watchCookie(entry.getKey());
      if (cookieFile == null || cookieFile.isBlank() || !Files.isRegularFile(Path.of(cookieFile))) {
        LOG.warn("event=YOUTUBE_WATCH_MARK_SKIPPED userId={} reason=watch_cookie_file_missing", entry.getKey());
        continue;
      }
      List<String> videoIds = List.copyOf(new LinkedHashSet<>(entry.getValue().stream().map(value -> value.getId().getVideoId()).toList()));
      try {
        markWatched(videoIds, current, cookieFile);
        for (WatchedVideoEntity watched : entry.getValue()) { watched.setYoutubeMarkedAt(ApplicationClock.now()); watched.setYoutubeMarkError(null); }
        watchedVideos.saveAll(entry.getValue());
        LOG.info("event=YOUTUBE_VIDEOS_MARKED_WATCHED userId={} videos={} records={}", entry.getKey(), videoIds.size(), entry.getValue().size());
      } catch (Exception e) {
        for (WatchedVideoEntity watched : entry.getValue()) watched.setYoutubeMarkError(safeMessage(e));
        watchedVideos.saveAll(entry.getValue());
        LOG.warn("event=YOUTUBE_WATCH_MARK_FAILED userId={} videos={} reason={}", entry.getKey(), videoIds.size(), safeMessage(e));
      }
    }
  }

  private void markWatched(List<String> videoIds, Map<String, String> current, String cookieFile) throws Exception {
    List<String> command = youtubeMarkCommand(videoIds, current, cookieFile,
        externalHttp.proxyArgument(current));
    Path output = Files.createTempFile(paths.root, "youtube-mark-watched-", ".log");
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true)
          .redirectOutput(output.toFile()).start();
      if (!process.waitFor(YTDLP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("yt-dlp timed out while marking watched");
      }
      if (process.exitValue() != 0) {
        String detail = Files.readString(output, StandardCharsets.UTF_8).trim();
        if (detail.length() > 500) detail = detail.substring(detail.length() - 500);
        throw new IllegalStateException("yt-dlp exited with " + process.exitValue()
            + (detail.isBlank() ? "" : ": " + detail));
      }
    } finally {
      Files.deleteIfExists(output);
    }
  }

  static List<String> youtubeMarkCommand(List<String> videoIds, Map<String, String> settings,
                                         String cookieFile, String proxy) {
    List<String> command = new ArrayList<>();
    command.add(settings.getOrDefault("yt_dlp_path", "yt-dlp"));
    // Marking watch history does not need a downloadable media format. Without
    // this, YouTube can accept the watch mark and yt-dlp still exits non-zero
    // while probing a restricted/formatless video, causing the whole batch to
    // be retried.
    command.addAll(List.of("--simulate", "--mark-watched", "--ignore-no-formats-error", "--no-playlist", "--no-warnings",
        "--cookies", cookieFile));
    if (proxy != null && !proxy.isBlank()) command.addAll(List.of("--proxy", proxy));
    String playerClient = settings.getOrDefault("youtube_player_client", "").trim();
    if (!playerClient.isBlank()) {
      command.addAll(List.of("--extractor-args", "youtube:player_client=" + playerClient));
    }
    command.add("--");
    for (String videoId : videoIds) {
      command.add("https://www.youtube.com/watch?v="
          + URLEncoder.encode(videoId, StandardCharsets.UTF_8));
    }
    return command;
  }

  private Map<Long, Path> userRoots() {
    Map<Long, Path> result = new HashMap<>();
    for (UserEntity user : users.findAll()) {
      result.put(user.getId(), paths.usersRoot.resolve(user.getFilesystemSlug()).toAbsolutePath().normalize());
    }
    return result;
  }

  static UserVideoEntity matchingLink(JellyfinClient.PlayedItem item, List<UserVideoEntity> links,
                                      Map<Long, Path> roots) {
    List<UserVideoEntity> candidates = links.stream()
        .filter(link -> item.videoId().isBlank() || item.videoId().equals(link.getId().getVideoId()))
        .toList();
    for (UserVideoEntity link : candidates) {
      Path root = roots.get(link.getId().getUserId());
      Path library = safeLibraryPath(link, root);
      if (library != null && pathMatches(item.path(), library, root)) return link;
    }
    // Provider ids are unambiguous in the common single-user setup. Avoid
    // deleting multiple users' copies when the same video is linked more than once.
    return !item.videoId().isBlank() && candidates.size() == 1 ? candidates.getFirst() : null;
  }

  static boolean pathMatches(String jellyfinPath, Path libraryPath, Path userRoot) {
    if (jellyfinPath == null || jellyfinPath.isBlank()) return false;
    if (JellyfinClient.sameOrChild(jellyfinPath, libraryPath)) return true;
    try {
      Path relative = userRoot.getFileName().resolve(userRoot.relativize(libraryPath));
      String normalizedJellyfin = jellyfinPath.replace('\\', '/');
      String suffix = relative.toString().replace('\\', '/');
      return normalizedJellyfin.equals(suffix) || normalizedJellyfin.contains("/" + suffix + "/")
          || normalizedJellyfin.endsWith("/" + suffix);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static Path safeLibraryPath(UserVideoEntity link, Path root) {
    if (link == null || root == null || link.getLibraryPath() == null) return null;
    try {
      Path path = Path.of(link.getLibraryPath()).toAbsolutePath().normalize();
      return path.startsWith(root) && !path.equals(root) ? path : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static void deleteTree(Path path, Path root) throws Exception {
    if (path == null || root == null || !path.startsWith(root) || path.equals(root) || !Files.exists(path)) return;
    if (Files.isSymbolicLink(path)) {
      Files.deleteIfExists(path);
      return;
    }
    try (var stream = Files.walk(path)) {
      for (Path candidate : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(candidate);
    }
  }

  private static void deleteIfEmpty(Path path, Path root) {
    if (path == null || root == null || path.equals(root) || !path.startsWith(root)) return;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
      if (!entries.iterator().hasNext()) Files.deleteIfExists(path);
    } catch (Exception ignored) { }
  }

  private static void removeOtherVideoFolders(Path root, String videoId) {
    if (root == null || videoId == null || videoId.isBlank() || !Files.isDirectory(root)) return;
    try (var stream = Files.walk(root, 2)) {
      for (Path candidate : stream.filter(Files::isDirectory)
          .filter(path -> videoId.equals(path.getFileName().toString())).toList()) {
        deleteTree(candidate, root);
        deleteIfEmpty(candidate.getParent(), root);
      }
    } catch (Exception ignored) { }
  }

  private static boolean enabled(Map<String, String> values, String key) {
    return "true".equalsIgnoreCase(values.getOrDefault(key, "false"));
  }

  private static String safeMessage(Exception e) {
    String value = e.getMessage();
    if (value == null || value.isBlank()) value = e.getClass().getSimpleName();
    return value.length() > 500 ? value.substring(0, 500) : value;
  }
}
