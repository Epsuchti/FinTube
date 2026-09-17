package ch.it4user.fintube.integration;

import ch.it4user.fintube.core.ApplicationPaths;
import ch.it4user.fintube.core.ApplicationClock;
import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.media.BackgroundFillService;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
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
  final ObjectMapper json = new ObjectMapper();
  final HttpClient http = HttpClient.newHttpClient();
  private final ConcurrentHashMap<String, Object> channelLocks = new ConcurrentHashMap<>();

  public YouTubeSyncService(SettingsService settings, YouTubeChannelRepository channels,
                            YouTubeSubscriptionRepository subscriptions, VideoRepository videos,
                            UserVideoRepository userVideos, UserRepository users,
                            ApplicationPaths paths, JellyfinSyncService jellyfin,
                            BackgroundFillService filler) {
    this.settings = settings;
    this.channels = channels;
    this.subscriptions = subscriptions;
    this.videos = videos;
    this.userVideos = userVideos;
    this.users = users;
    this.paths = paths;
    this.jellyfin = jellyfin;
    this.filler = filler;
  }

  /** Synchronize once, then ensure the requested user's known videos are linked. */
  public int sync(String channel, long userId) throws Exception {
    int discovered = syncChannel(channel);
    linkExistingVideos(channel, userId);
    return discovered;
  }

  /**
   * Synchronize a canonical channel once for all enabled subscribers. The
   * first run honors initial_channel_import_count; later runs use the
   * persisted latest published timestamp and fetch only newer pages.
   */
  public int syncChannel(String channel) throws Exception {
    if (channel == null || channel.isBlank()) throw new IllegalArgumentException("channel is required");
    Object lock = channelLocks.computeIfAbsent(channel, ignored -> new Object());
    synchronized (lock) {
      return syncChannelLocked(channel);
    }
  }

  private int syncChannelLocked(String channel) throws Exception {
    Map<String, String> currentSettings = settings();
    String key = currentSettings.get("youtube_api_key");
    if (key == null || key.isBlank()) throw new IllegalStateException("YouTube API key is not configured");

    String attemptAt = now();
    markChannelAttempt(channel, attemptAt);
    try {
      String cursor = channelCursor(channel);
      List<JsonNode> searchItems = discover(channel, key, cursor, currentSettings);
      List<String> ids = new ArrayList<>();
      String newestPublished = cursor;
      for (JsonNode item : searchItems) {
        String id = item.path("id").path("videoId").asText();
        if (id.isBlank() || ids.contains(id)) continue;
        ids.add(id);
        newestPublished = maxPublished(newestPublished, item.path("snippet").path("publishedAt").asText());
      }
      // Incremental search intentionally does not return older uploads. Probe
      // a bounded set of stale canonical rows as well, so a deleted/private
      // video that disappeared from the API is eventually marked unavailable
      // without re-importing an entire channel history on every run.
      for (String stale : staleVideoIds(channel)) if (!ids.contains(stale)) ids.add(stale);

      Set<String> returned = new HashSet<>();
      int count = 0;
      for (int start = 0; start < ids.size(); start += 50) {
        int end = Math.min(ids.size(), start + 50);
        String batch = String.join(",", ids.subList(start, end));
        JsonNode details = request("https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails,status&id="
            + enc(batch) + "&key=" + enc(key));
        for (JsonNode video : details.path("items")) {
          String videoId = video.path("id").asText();
          if (videoId.isBlank()) continue;
          returned.add(videoId);
          upsertVideo(video, channel);
          newestPublished = maxPublished(newestPublished, video.path("snippet").path("publishedAt").asText());
          count++;
        }
      }

      // A result can disappear between search.list and videos.list because it
      // was deleted, made private, or rejected. Keep its metadata row but
      // explicitly mark it unavailable instead of presenting it as playable.
      markMissingAsUnavailable(channel, ids, returned);

      String successAt = now();
      updateChannelSuccess(channel, successAt, newestPublished);
      markSubscriptionsSynced(channel, successAt);
      linkEnabledSubscribers(channel);
      prefetchNewest(channel, currentSettings);
      return count;
    } catch (Exception failure) {
      markChannelFailure(channel, now(), safeError(failure));
      throw failure;
    }
  }

  /** Enqueue globally shared, low-priority cache fills for a channel's latest X videos. */
  private void prefetchNewest(String channel, Map<String, String> settings) {
    int limit;
    try { limit = Math.max(0, Math.min(1000, Integer.parseInt(settings.getOrDefault("newest_videos_to_download", "0")))); }
    catch (NumberFormatException ignored) { return; }
    if (limit == 0) return;
    try {
      videos.findByChannelIdAndAvailabilityOrderByPublishedAtDesc(
          channel, "AVAILABLE", PageRequest.of(0, limit))
          .forEach(video -> filler.enqueue(video.getVideoId()));
    } catch (Exception ignored) {
      // Prefetch must never fail metadata synchronization or interactive playback.
    }
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

  private List<JsonNode> discover(String channel, String key, String cursor,
                                  Map<String, String> settings) throws Exception {
    boolean initial = cursor == null || cursor.isBlank();
    int initialLimit;
    try {
      initialLimit = Math.max(1, Math.min(50,
          Integer.parseInt(settings.getOrDefault("initial_channel_import_count", "20"))));
    } catch (NumberFormatException e) {
      initialLimit = 20;
    }
    int limit = initial ? initialLimit : 50;
    List<JsonNode> items = new ArrayList<>();
    String page = null;
    int pages = 0;
    do {
      StringBuilder url = new StringBuilder("https://www.googleapis.com/youtube/v3/search?part=snippet&channelId=")
          .append(enc(channel)).append("&type=video&order=date&maxResults=").append(limit)
          .append("&key=").append(enc(key));
      if (!initial) url.append("&publishedAfter=").append(enc(afterCursor(cursor)));
      if (page != null && !page.isBlank()) url.append("&pageToken=").append(enc(page));
      JsonNode root = request(url.toString());
      root.path("items").forEach(items::add);
      page = root.path("nextPageToken").asText("");
      pages++;
    } while (!initial && page != null && !page.isBlank() && pages < MAX_INCREMENTAL_PAGES);
    return items;
  }

  private String channelCursor(String channel) {
    return channels.findById(channel)
        .map(YouTubeChannelEntity::getLastSyncPublishedAt)
        .orElseThrow(() -> new IllegalArgumentException("unknown YouTube channel " + channel));
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
      entity.setLastCheckedAt(at);
      entity.setLastSuccessfulSyncAt(at);
    });
    if (!entities.isEmpty()) subscriptions.saveAll(entities);
  }

  private void linkEnabledSubscribers(String channel) throws Exception {
    for (YouTubeSubscriptionEntity subscription : subscriptions.findByChannelIdAndEnabled(channel, 1)) {
      linkExistingVideos(channel, subscription.getUserId());
    }
  }

  private void linkExistingVideos(String channel, long user) throws Exception {
    for (VideoEntity video : videos.findByChannelIdOrderByPublishedAtDesc(channel)) {
      linkStoredVideo(user, video.getVideoId());
    }
  }

  private void upsertVideo(JsonNode video, String channel) throws Exception {
    String id = video.path("id").asText();
    JsonNode snippet = video.path("snippet");
    int duration = duration(video.path("contentDetails").path("duration").asText());
    String availability = availability(video.path("status"));
    VideoEntity entity = videos.findById(id).orElseGet(() -> new VideoEntity(
        id, channel, "", "", null, 0, 0, "", "AVAILABLE", now()));
    entity.setChannelId(channel);
    entity.setTitle(snippet.path("title").asText(""));
    entity.setDescription(snippet.path("description").asText(""));
    entity.setPublishedAt(snippet.path("publishedAt").asText(""));
    entity.setDurationSeconds(duration);
    entity.setIsShort(isShort(video, duration) ? 1 : 0);
    entity.setThumbnailUrl(thumbnail(snippet));
    entity.setAvailability(availability);
    entity.setMetadataUpdatedAt(now());
    videos.save(entity);
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
        video, entity.getTitle(), entity.getDescription(), entity.getPublishedAt(),
        entity.getDurationSeconds(), entity.getThumbnailUrl(), entity.getAvailability());
  }

  // Compatibility wrapper for callers from older builds.
  void linkLibrary(long user, JsonNode video) throws Exception {
    String id = video.path("id").asText();
    if (!id.isBlank()) linkStoredVideo(user, id);
  }

  private void writeLibrary(long user, String slug, String channelName, String channel, String video,
                            String title, String description, String published, int duration,
                            String thumbnail, String availability) throws Exception {
    Path root = paths.usersRoot.resolve(slug).toAbsolutePath().normalize();
    if (!root.startsWith(paths.usersRoot.toAbsolutePath().normalize()))
      throw new SecurityException("invalid user filesystem root");
    Path path = root.resolve(clean(channelName) + " [" + clean(channel) + "]").resolve(video).normalize();
    if (!path.startsWith(root) || path.equals(root)) throw new SecurityException("invalid library path");
    Files.createDirectories(path);
    UserVideoId linkId = new UserVideoId(user, video);
    UserVideoEntity link = userVideos.findById(linkId).orElse(null);
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
      HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
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
    HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2)
      throw new IllegalStateException("YouTube Data API status " + response.statusCode());
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

  private static String afterCursor(String cursor) {
    try { return Instant.parse(cursor).minusSeconds(1).toString(); }
    catch (DateTimeParseException e) { return cursor; }
  }

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
}
