package ch.it4user.fintube.integration;

import ch.it4user.fintube.core.ApplicationPaths;
import ch.it4user.fintube.core.ApplicationClock;
import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.media.BackgroundFillService;
import ch.it4user.fintube.media.ProxiedHttpClient;
import ch.it4user.fintube.persistence.entities.UserEntity;
import ch.it4user.fintube.persistence.repositories.UserRepository;
import ch.it4user.fintube.persistence.entities.UserVideoEntity;
import ch.it4user.fintube.persistence.entities.UserVideoId;
import ch.it4user.fintube.persistence.repositories.UserVideoRepository;
import ch.it4user.fintube.persistence.entities.VideoEntity;
import ch.it4user.fintube.persistence.repositories.VideoRepository;
import ch.it4user.fintube.persistence.entities.YouTubeChannelEntity;
import ch.it4user.fintube.persistence.repositories.YouTubeChannelRepository;
import ch.it4user.fintube.persistence.entities.YouTubeSubscriptionEntity;
import ch.it4user.fintube.persistence.repositories.YouTubeSubscriptionRepository;
import ch.it4user.fintube.service.UserYouTubeApiKeyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Official YouTube Data API ingestion.
 *
 * A channel is synchronized once globally, regardless of how many users
 * subscribe to it. The resulting canonical metadata rows and generated
 * library files are then linked to every enabled subscriber.
 */
@Service
public class YouTubeSyncService {
  private static final Logger LOG = LoggerFactory.getLogger(YouTubeSyncService.class);
  private static final Pattern ISO_DURATION =
      Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?");
  private static final int MAX_INCREMENTAL_PAGES = 20;

  final SettingsService settings;
  final YouTubeChannelRepository channels;
  final YouTubeSubscriptionRepository subscriptions;
  final VideoRepository videos;
  final UserVideoRepository userVideos;
  final UserRepository users;
  final ApplicationPaths paths;
  final JellyfinSyncService jellyfin;
  final BackgroundFillService filler;
  final UserYouTubeApiKeyService youtubeApiKeys;
  final ObjectMapper json = new ObjectMapper();
  final ProxiedHttpClient externalHttp;
  private final ConcurrentHashMap<String, Object> channelLocks = new ConcurrentHashMap<>();

  public YouTubeSyncService(SettingsService settings, YouTubeChannelRepository channels,
                            YouTubeSubscriptionRepository subscriptions, VideoRepository videos,
                            UserVideoRepository userVideos, UserRepository users,
                            ApplicationPaths paths, JellyfinSyncService jellyfin,
                            BackgroundFillService filler, UserYouTubeApiKeyService youtubeApiKeys,
                            ProxiedHttpClient externalHttp) {
    this.settings = settings;
    this.channels = channels;
    this.subscriptions = subscriptions;
    this.videos = videos;
    this.userVideos = userVideos;
    this.users = users;
    this.paths = paths;
    this.jellyfin = jellyfin;
    this.filler = filler;
    this.youtubeApiKeys = youtubeApiKeys;
    this.externalHttp = externalHttp;
  }

  /** Synchronize once, then ensure the requested user's known videos are linked. */
  public int sync(String channel, long userId) throws Exception {
    try (JellyfinSyncService.RefreshBatch ignored = jellyfin.beginRefreshBatch()) {
      int discovered = syncChannel(channel, userId);
      linkExistingVideos(channel, userId);
      return discovered;
    }
  }

  /**
   * Synchronize a canonical channel once for all enabled subscribers. The
   * first run honors initial_channel_import_count; later runs use the
   * persisted latest published timestamp and fetch only newer pages.
   */
  public int syncChannel(String channel, long userId) throws Exception {
    if (channel == null || channel.isBlank()) throw new IllegalArgumentException("channel is required");
    Object lock = channelLocks.computeIfAbsent(channel, ignored -> new Object());
    synchronized (lock) {
      return syncChannelLocked(channel, userId);
    }
  }

  private int syncChannelLocked(String channel, long userId) throws Exception {
    Map<String, String> currentSettings = settings();
    String key = youtubeApiKeys.key(userId);
    if (key == null || key.isBlank()) throw new IllegalStateException("YouTube Data API key is not configured for this user");

    String attemptAt = now();
    markChannelAttempt(channel, attemptAt);
    try {
      YouTubeChannelEntity channelEntity = channelEntity(channel);
      ensureChannelMetadata(channelEntity, key);
      String cursor = channelEntity.getLastSyncPublishedAt();
      boolean initial = cursor == null || cursor.isBlank();
      List<JsonNode> playlistItems = discover(channelEntity, key, cursor, currentSettings);
      List<String> ids = new ArrayList<>();
      String newestPublished = cursor;
      for (JsonNode item : playlistItems) {
        String id = playlistVideoId(item);
        if (id.isBlank() || ids.contains(id)) continue;
        ids.add(id);
        newestPublished = maxPublished(newestPublished, playlistPublishedAt(item));
      }
      // Incremental playlist reads stop at the persisted cursor. Probe
      // a bounded set of stale canonical rows as well, so a deleted/private
      // video that disappeared from the API is eventually marked unavailable
      // without re-importing an entire channel history on every run.
      for (String stale : staleVideoIds(channel)) if (!ids.contains(stale)) ids.add(stale);

      Set<String> returned = new HashSet<>();
      List<String> importedIds = new ArrayList<>();
      int[] categoryCounts = new int[3];
      int count = 0;
      for (int start = 0; start < ids.size(); start += 50) {
        int end = Math.min(ids.size(), start + 50);
        String batch = String.join(",", ids.subList(start, end));
        JsonNode details = request("https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails,status,liveStreamingDetails&id="
            + enc(batch) + "&key=" + enc(key));
        for (JsonNode video : details.path("items")) {
          String videoId = video.path("id").asText();
          if (videoId.isBlank()) continue;
          if (initial && !acceptedInitialCategory(video, channelEntity, categoryCounts, currentSettings)) continue;
          returned.add(videoId);
          importedIds.add(videoId);
          boolean newVideo = upsertVideo(video, channel);
          newestPublished = maxPublished(newestPublished, video.path("snippet").path("publishedAt").asText());
          if (newVideo) count++;
        }
      }

      // A result can disappear between playlistItems.list and videos.list because it
      // was deleted, made private, or rejected. Keep its metadata row but
      // explicitly mark it unavailable instead of presenting it as playable.
      markMissingAsUnavailable(channel, importedIds, returned);

      String successAt = now();
      updateChannelSuccess(channel, successAt, newestPublished);
      markSubscriptionsSynced(channel, successAt);
      linkEnabledSubscribers(channel);
      prefetchNewest(channel);
      return count;
    } catch (Exception failure) {
      markChannelFailure(channel, now(), safeError(failure));
      throw failure;
    }
  }

  /** Enqueue globally shared, low-priority cache fills for a channel's latest X videos. */
  private void prefetchNewest(String channel) {
    YouTubeChannelEntity channelEntity = channels.findById(channel).orElse(null);
    if (channelEntity == null) return;
    int videoLimit = bounded(channelEntity.getDownloadCount());
    int shortLimit = bounded(channelEntity.getShortDownloadCount());
    int liveStreamLimit = bounded(channelEntity.getLiveStreamDownloadCount());
    if (videoLimit == 0 && shortLimit == 0 && liveStreamLimit == 0) return;
    try {
      List<VideoEntity> videosToPrefetch = prefetchCategory(channel, videoLimit, 0, 0);
      List<VideoEntity> shortsToPrefetch = prefetchCategory(channel, shortLimit, 1, 0);
      List<VideoEntity> liveStreamsToPrefetch = prefetchCategory(channel, liveStreamLimit, 0, 1);
      LOG.info("event=YOUTUBE_PREFETCH_REQUESTED channel={} videoLimit={} shortLimit={} liveStreamLimit={} videos={} shorts={} liveStreams={}",
          channel, videoLimit, shortLimit, liveStreamLimit, videosToPrefetch.size(), shortsToPrefetch.size(), liveStreamsToPrefetch.size());
      videosToPrefetch.forEach(video -> filler.enqueue(video.getVideoId(), "youtube_prefetch_video"));
      shortsToPrefetch.forEach(video -> filler.enqueue(video.getVideoId(), "youtube_prefetch_short"));
      liveStreamsToPrefetch.forEach(video -> filler.enqueue(video.getVideoId(), "youtube_prefetch_live_stream"));
    } catch (Exception ignored) {
      // Prefetch must never fail metadata synchronization or interactive playback.
      LOG.warn("event=YOUTUBE_PREFETCH_FAILED channel={} reason={}", channel, ignored.toString(), ignored);
    }
  }

  private List<VideoEntity> prefetchCategory(String channel, int limit, int isShort, int isLiveStream) {
    if (limit == 0) return List.of();
    return videos.findByChannelIdAndAvailabilityAndIsShortAndIsLiveStreamOrderByPublishedAtDesc(
        channel, "AVAILABLE", isShort, isLiveStream, PageRequest.of(0, limit));
  }

  /**
   * Remove one user's subscription and only that user's generated library
   * records/files. Canonical metadata and shared media cache are retained.
   */
  public boolean removeSubscription(long userId, long subscriptionId) throws Exception {
    YouTubeSubscriptionEntity subscription = subscriptions.findByIdAndUserId(subscriptionId, userId).orElse(null);
    if (subscription == null) return false;
    UserEntity user = users.findById(userId).orElse(null);
    if (user == null) return false;

    String channel = subscription.getChannelId();
    Path userRoot = paths.usersRoot.resolve(user.getFilesystemSlug()).toAbsolutePath().normalize();
    List<String> videoIds = videos.findByChannelIdOrderByPublishedAtDesc(channel).stream()
        .map(VideoEntity::getVideoId)
        .toList();
    List<Path> libraryPaths = new ArrayList<>();
    if (!videoIds.isEmpty()) {
      List<UserVideoEntity> links = userVideos.findAllById(
          videoIds.stream().map(video -> new UserVideoId(userId, video)).toList());
      for (UserVideoEntity link : links) {
        String storedPath = link.getLibraryPath();
        if (storedPath == null) continue;
        Path candidate = Path.of(storedPath).toAbsolutePath().normalize();
        if (candidate.startsWith(userRoot) && !candidate.equals(userRoot)) libraryPaths.add(candidate);
      }
      userVideos.deleteAllInBatch(links);
    }
    subscriptions.delete(subscription);

    // Delete after repository writes so filesystem errors cannot leave database locks.
    for (Path path : libraryPaths) deleteTreeSafely(path, userRoot);
    for (Path path : libraryPaths) deleteIfEmpty(path.getParent(), userRoot);
    return true;
  }

  private List<JsonNode> discover(YouTubeChannelEntity channelEntity, String key, String cursor,
                                  Map<String, String> settings) throws Exception {
    boolean initial = cursor == null || cursor.isBlank();
    int initialLimit = initialImportCount(channelEntity.getChannelId(), settings)
        + initialShortImportCount(channelEntity)
        + initialLiveStreamImportCount(channelEntity);
    String playlistId = channelEntity.getUploadsPlaylistId();
    if (playlistId == null || playlistId.isBlank()) {
      playlistId = uploadsPlaylist(channelEntity.getChannelId(), key);
      channelEntity.setUploadsPlaylistId(playlistId);
      channelEntity.setUpdatedAt(now());
      channels.save(channelEntity);
    }
    List<JsonNode> items = new ArrayList<>();
    String page = null;
    int pages = 0;
    do {
      if (initial && initialLimit == 0) return items;
      int remaining = initial ? Math.max(1, initialLimit - items.size()) : 50;
      StringBuilder url = new StringBuilder("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet,contentDetails&playlistId=")
          .append(enc(playlistId)).append("&maxResults=")
          .append(Math.min(50, remaining))
          .append("&key=").append(enc(key));
      if (page != null && !page.isBlank()) url.append("&pageToken=").append(enc(page));
      JsonNode root = request(url.toString());
      boolean reachedCursor = false;
      for (JsonNode item : root.path("items")) {
        String published = playlistPublishedAt(item);
        if (initial || newerOrEqual(published, cursor)) items.add(item);
        if (!initial && olderThan(published, cursor)) reachedCursor = true;
      }
      page = root.path("nextPageToken").asText("");
      pages++;
      if (!initial && reachedCursor) break;
    } while (page != null && !page.isBlank() && pages < MAX_INCREMENTAL_PAGES
        && (!initial || items.size() < initialLimit));
    return items;
  }

  private YouTubeChannelEntity channelEntity(String channel) {
    return channels.findById(channel)
        .orElseThrow(() -> new IllegalArgumentException("unknown YouTube channel " + channel));
  }

  private void ensureChannelMetadata(YouTubeChannelEntity channelEntity, String key) {
    boolean missingThumbnail = channelEntity.getThumbnailUrl() == null || channelEntity.getThumbnailUrl().isBlank();
    boolean missingPlaylist = channelEntity.getUploadsPlaylistId() == null || channelEntity.getUploadsPlaylistId().isBlank();
    if (!missingThumbnail && !missingPlaylist) return;
    try {
      JsonNode item = request("https://www.googleapis.com/youtube/v3/channels?part=snippet,contentDetails&id="
          + enc(channelEntity.getChannelId()) + "&key=" + enc(key)).path("items").path(0);
      if (!item.isObject()) return;
      boolean changed = false;
      String name = item.path("snippet").path("title").asText("");
      String thumbnail = thumbnail(item.path("snippet"));
      String playlist = item.path("contentDetails").path("relatedPlaylists").path("uploads").asText("");
      if (!name.isBlank() && !name.equals(channelEntity.getName())) {
        channelEntity.setName(name);
        changed = true;
      }
      if (!thumbnail.isBlank() && !thumbnail.equals(channelEntity.getThumbnailUrl())) {
        channelEntity.setThumbnailUrl(thumbnail);
        changed = true;
      }
      if (!playlist.isBlank() && !playlist.equals(channelEntity.getUploadsPlaylistId())) {
        channelEntity.setUploadsPlaylistId(playlist);
        changed = true;
      }
      if (changed) {
        channelEntity.setUpdatedAt(now());
        channels.save(channelEntity);
      }
    } catch (Exception ignored) { }
  }

  private String uploadsPlaylist(String channel, String key) throws Exception {
    JsonNode root = request("https://www.googleapis.com/youtube/v3/channels?part=contentDetails&id="
        + enc(channel) + "&key=" + enc(key));
    String playlist = root.path("items").path(0).path("contentDetails")
        .path("relatedPlaylists").path("uploads").asText("");
    if (playlist.isBlank()) throw new IllegalStateException("YouTube uploads playlist not found for channel " + channel);
    return playlist;
  }

  private int initialImportCount(String channel, Map<String, String> settings) {
    Integer configured = channels.findById(channel)
        .map(YouTubeChannelEntity::getInitialImportCount)
        .orElse(null);
    if (configured != null) return Math.max(0, Math.min(1000, configured));
    try {
      return Math.max(0, Math.min(1000,
          Integer.parseInt(settings.getOrDefault("initial_channel_import_count", "20"))));
    } catch (NumberFormatException ignored) {
      return 20;
    }
  }

  private int initialShortImportCount(YouTubeChannelEntity channel) {
    return bounded(channel.getShortImportCount());
  }

  private int initialLiveStreamImportCount(YouTubeChannelEntity channel) {
    return bounded(channel.getLiveStreamImportCount());
  }

  private static int bounded(Integer value) {
    return value == null ? 0 : Math.max(0, Math.min(1000, value));
  }

  private boolean acceptedInitialCategory(JsonNode video, YouTubeChannelEntity channel,
                                          int[] categoryCounts, Map<String, String> currentSettings) {
    int duration = duration(video.path("contentDetails").path("duration").asText());
    if (isPastLiveStream(video)) {
      return categoryCounts[2]++ < initialLiveStreamImportCount(channel);
    }
    if (isShort(video, duration)) {
      return categoryCounts[1]++ < initialShortImportCount(channel);
    }
    return categoryCounts[0]++ < initialImportCount(channel.getChannelId(), currentSettings);
  }

  private static boolean isPastLiveStream(JsonNode video) {
    return !video.path("liveStreamingDetails").path("actualEndTime").asText("").isBlank();
  }

  private void markChannelAttempt(String channel, String at) {
    YouTubeChannelEntity entity = channels.findById(channel)
        .orElseThrow(() -> new IllegalArgumentException("unknown YouTube channel " + channel));
    entity.setLastSyncAt(at);
    entity.setSyncError(null);
    channels.save(entity);
  }

  private void updateChannelSuccess(String channel, String at, String cursor) {
    YouTubeChannelEntity entity = channels.findById(channel).orElse(null);
    if (entity == null) return;
    entity.setLastSyncAt(at);
    entity.setLastSuccessfulSyncAt(at);
    entity.setLastSyncPublishedAt(cursor == null || cursor.isBlank() ? null : cursor);
    entity.setSyncError(null);
    channels.save(entity);
  }

  private void markChannelFailure(String channel, String at, String error) {
    try {
      YouTubeChannelEntity entity = channels.findById(channel).orElse(null);
      if (entity == null) return;
      entity.setLastSyncAt(at);
      entity.setSyncError(error);
      channels.save(entity);
    } catch (Exception ignored) { }
  }

  private void markSubscriptionsSynced(String channel, String at) {
    List<YouTubeSubscriptionEntity> entities = subscriptions.findByChannelIdAndEnabled(channel, 1);
    entities.forEach(entity -> {
      if (!youtubeApiKeys.configured(entity.getUserId())) return;
      entity.setLastCheckedAt(at);
      entity.setLastSuccessfulSyncAt(at);
    });
    if (!entities.isEmpty()) subscriptions.saveAll(entities);
  }

  private void linkEnabledSubscribers(String channel) throws Exception {
    for (YouTubeSubscriptionEntity subscription : subscriptions.findByChannelIdAndEnabled(channel, 1)) {
      if (!youtubeApiKeys.configured(subscription.getUserId())) continue;
      linkExistingVideos(channel, subscription.getUserId());
    }
  }

  private void linkExistingVideos(String channel, long user) throws Exception {
    for (VideoEntity video : videos.findByChannelIdOrderByPublishedAtDesc(channel)) {
      linkStoredVideo(user, video.getVideoId());
    }
  }

  private boolean upsertVideo(JsonNode video, String channel) throws Exception {
    String id = video.path("id").asText();
    JsonNode snippet = video.path("snippet");
    int duration = duration(video.path("contentDetails").path("duration").asText());
    String availability = availability(video.path("status"));
    VideoEntity entity = videos.findById(id).orElse(null);
    boolean newVideo = entity == null;
    if (entity == null) {
      entity = new VideoEntity(id, channel, "", "", null, 0, 0, "", "AVAILABLE", now());
    }
    entity.setChannelId(channel);
    entity.setTitle(snippet.path("title").asText(""));
    entity.setDescription(snippet.path("description").asText(""));
    entity.setPublishedAt(snippet.path("publishedAt").asText(""));
    entity.setDurationSeconds(duration);
    entity.setIsShort(isShort(video, duration) ? 1 : 0);
    entity.setIsLiveStream(isPastLiveStream(video) ? 1 : 0);
    entity.setThumbnailUrl(thumbnail(snippet));
    entity.setAvailability(availability);
    entity.setMetadataUpdatedAt(now());
    videos.save(entity);
    return newVideo;
  }

  private void markMissingAsUnavailable(String channel, List<String> ids, Set<String> returned) {
    if (ids.isEmpty()) return;
    List<VideoEntity> entities = videos.findByChannelIdAndVideoIdIn(channel, ids);
    entities.removeIf(entity -> returned.contains(entity.getVideoId()));
    entities.forEach(entity -> {
      entity.setAvailability("UNAVAILABLE");
      entity.setMetadataUpdatedAt(now());
    });
    if (!entities.isEmpty()) videos.saveAll(entities);
  }

  private List<String> staleVideoIds(String channel) {
    String cutoff = Instant.now().minusSeconds(24 * 60 * 60L).toString();
    return videos.findStaleVideoIds(channel, cutoff, PageRequest.of(0, 50));
  }

  /** Link one canonical metadata row into one user's isolated Jellyfin tree. */
  private void linkStoredVideo(long user, String video) throws Exception {
    VideoEntity entity = videos.findById(video).orElse(null);
    if (entity == null) return;
    if (subscriptions.findByUserIdAndChannelIdAndEnabled(user, entity.getChannelId(), 1).isEmpty()) return;
    UserEntity userEntity = users.findById(user).orElse(null);
    YouTubeChannelEntity channelEntity = channels.findById(entity.getChannelId()).orElse(null);
    if (userEntity == null || channelEntity == null) return;
    writeLibrary(user, userEntity.getFilesystemSlug(), channelEntity.getName(), entity.getChannelId(),
        channelEntity.getThumbnailUrl(), video, entity.getTitle(), entity.getDescription(), entity.getPublishedAt(),
        entity.getDurationSeconds(), entity.getThumbnailUrl(), entity.getAvailability());
  }

  // Compatibility wrapper for callers from older builds.
  void linkLibrary(long user, JsonNode video) throws Exception {
    String id = video.path("id").asText();
    if (!id.isBlank()) linkStoredVideo(user, id);
  }

  private void writeLibrary(long user, String slug, String channelName, String channel, String channelThumbnail,
                            String video,
                            String title, String description, String published, int duration,
                            String thumbnail, String availability) throws Exception {
    Path root = paths.usersRoot.resolve(slug).toAbsolutePath().normalize();
    if (!root.startsWith(paths.usersRoot.toAbsolutePath().normalize()))
      throw new SecurityException("invalid user filesystem root");
    Path channelPath = root.resolve(clean(channelName)).normalize();
    if (!channelPath.startsWith(root) || channelPath.equals(root)) throw new SecurityException("invalid channel library path");
    Path path = channelPath.resolve(video).normalize();
    if (!path.startsWith(root) || path.equals(root)) throw new SecurityException("invalid library path");
    UserVideoId linkId = new UserVideoId(user, video);
    UserVideoEntity link = userVideos.findById(linkId).orElse(null);
    Path existingPath = link == null ? null : Path.of(link.getLibraryPath()).toAbsolutePath().normalize();
    migrateLibraryDirectory(existingPath, path, root);
    Files.createDirectories(path);
    downloadThumbnail(channelThumbnail, channelPath.resolve("folder.jpg"));
    String token = link == null ? UUID.randomUUID().toString().replace("-", "") : link.getPlaybackToken();
    String base = settings().getOrDefault("public_base_url", "http://localhost:8080");
    Files.writeString(path.resolve("video.strm"), base + "/play/" + video + "?token=" + token + "\n");
    String date = published == null ? "" : published.length() >= 10 ? published.substring(0, 10) : published;
    String nfo = "<movie><title>" + xml(title) + "</title><plot>" + xml(description) + "</plot><studio>"
        + xml(channelName) + "</studio><runtime>" + Math.max(1, duration / 60)
        + "</runtime><uniqueid type=\"youtube\">" + xml(video) + "</uniqueid><premiered>" + xml(date)
        + "</premiered><tagline>" + xml(availability) + "</tagline></movie>";
    Files.writeString(path.resolve("video.nfo"), nfo);
    downloadThumbnail(thumbnail, path.resolve("video-thumb.jpg"));
    if (link == null) {
      link = new UserVideoEntity(linkId, path.toString(), token, now());
    } else {
      link.setLibraryPath(path.toString());
    }
    userVideos.save(link);
    jellyfin.afterLibraryGeneration(video, path, duration);
  }

  private void downloadThumbnail(String url, Path target) {
    if (url == null || url.isBlank() || Files.exists(target)) return;
    try {
      HttpResponse<byte[]> response = externalHttp.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() / 100 == 2) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, response.body());
        try {
          Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
          Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    } catch (Exception ignored) { }
  }

  private JsonNode request(String url) throws Exception {
    HttpResponse<String> response = externalHttp.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      String detail = "";
      try {
        JsonNode error = json.readTree(response.body()).path("error");
        String reason = error.path("errors").path(0).path("reason").asText("");
        String message = error.path("message").asText("");
        if (!reason.isBlank()) detail = ": " + reason + (message.isBlank() ? "" : " (" + message + ")");
      } catch (Exception ignored) { }
      throw new IllegalStateException("YouTube Data API status " + response.statusCode() + detail);
    }
    return json.readTree(response.body());
  }

  private Map<String, String> settings() {
    return settings.values(false);
  }

  private static String now() {
    return ApplicationClock.now();
  }

  private static String availability(JsonNode status) {
    String privacy = status.path("privacyStatus").asText("").toLowerCase();
    String upload = status.path("uploadStatus").asText("").toLowerCase();
    if (privacy.equals("private")) return "PRIVATE";
    if (privacy.equals("deleted") || upload.equals("deleted")) return "DELETED";
    if (upload.equals("failed") || upload.equals("rejected")) return "UNAVAILABLE";
    return "AVAILABLE";
  }

  private static boolean isShort(JsonNode video, int seconds) {
    if (seconds > 0 && seconds <= 60) return true;
    JsonNode snippet = video.path("snippet");
    if (containsShorts(snippet.path("title").asText()) || containsShorts(snippet.path("description").asText())) return true;
    for (JsonNode tag : snippet.path("tags")) if (containsShorts(tag.asText())) return true;
    return false;
  }

  private static boolean containsShorts(String value) {
    return value != null && value.toLowerCase().matches(".*(^|[^a-z])#?shorts([^a-z]|$).*");
  }

  static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

  private static String playlistVideoId(JsonNode item) {
    String id = item.path("contentDetails").path("videoId").asText("");
    return id.isBlank() ? item.path("snippet").path("resourceId").path("videoId").asText("") : id;
  }

  private static String playlistPublishedAt(JsonNode item) {
    String published = item.path("contentDetails").path("videoPublishedAt").asText("");
    return published.isBlank() ? item.path("snippet").path("publishedAt").asText("") : published;
  }

  private static boolean newerOrEqual(String candidate, String cursor) {
    if (cursor == null || cursor.isBlank() || candidate == null || candidate.isBlank()) return true;
    try { return !Instant.parse(candidate).isBefore(Instant.parse(cursor)); }
    catch (DateTimeParseException e) { return candidate.compareTo(cursor) >= 0; }
  }

  private static boolean olderThan(String candidate, String cursor) {
    if (cursor == null || cursor.isBlank() || candidate == null || candidate.isBlank()) return false;
    try { return Instant.parse(candidate).isBefore(Instant.parse(cursor)); }
    catch (DateTimeParseException e) { return candidate.compareTo(cursor) < 0; }
  }

  static String thumbnail(JsonNode snippet) {
    for (String key : List.of("maxres", "standard", "high", "medium", "default")) {
      String value = snippet.path("thumbnails").path(key).path("url").asText();
      if (!value.isBlank()) return value;
    }
    return "";
  }

  static int duration(String iso) {
    Matcher matcher = ISO_DURATION.matcher(iso == null ? "" : iso);
    if (!matcher.matches()) return 0;
    return number(matcher.group(1)) * 3600 + number(matcher.group(2)) * 60 + number(matcher.group(3));
  }

  private static int number(String value) { return value == null ? 0 : Integer.parseInt(value); }

  private static String maxPublished(String current, String candidate) {
    if (candidate == null || candidate.isBlank()) return current;
    if (current == null || current.isBlank()) return candidate;
    try { return Instant.parse(candidate).isAfter(Instant.parse(current)) ? candidate : current; }
    catch (DateTimeParseException e) { return candidate.compareTo(current) > 0 ? candidate : current; }
  }

  private static String clean(String value) {
    if (value == null || value.isBlank()) return "channel";
    String cleaned = value.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
    return cleaned.isBlank() ? "channel" : cleaned;
  }

  private static String xml(String value) {
    if (value == null) return "";
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;");
  }

  private static String safeError(Exception e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) return e.getClass().getSimpleName();
    return message.length() > 500 ? message.substring(0, 500) : message;
  }

  private static void deleteTreeSafely(Path path, Path root) throws Exception {
    if (path == null || root == null || !path.startsWith(root) || path.equals(root) || !Files.exists(path)) return;
    if (Files.isSymbolicLink(path)) { Files.deleteIfExists(path); return; }
    try (var stream = Files.walk(path)) {
      stream.sorted(Comparator.reverseOrder()).forEach(candidate -> {
        try { Files.deleteIfExists(candidate); } catch (Exception ignored) { }
      });
    }
  }

  private static void deleteIfEmpty(Path path, Path root) {
    if (path == null || root == null || path.equals(root) || !path.startsWith(root)) return;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
      if (!entries.iterator().hasNext()) Files.deleteIfExists(path);
    } catch (Exception ignored) { }
  }

  private static void migrateLibraryDirectory(Path current, Path target, Path root) throws IOException {
    if (current == null || current.equals(target) || !Files.exists(current)
        || !current.startsWith(root) || current.equals(root)
        || !target.startsWith(root) || target.equals(root) || Files.isSymbolicLink(current)) return;
    if (!Files.isDirectory(current)) return;
    Files.createDirectories(target);
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(current)) {
      for (Path source : entries) {
        Path destination = target.resolve(source.getFileName()).normalize();
        if (!destination.startsWith(target)) continue;
        if (Files.exists(destination)) {
          if (Files.isDirectory(source) && Files.isDirectory(destination)) {
            migrateLibraryDirectory(source, destination, target);
          } else {
            Files.deleteIfExists(source);
          }
        } else {
          Files.move(source, destination);
        }
      }
    }
    Files.deleteIfExists(current);
    deleteIfEmpty(current.getParent(), root);
  }
}
