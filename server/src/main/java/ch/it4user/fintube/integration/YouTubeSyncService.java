package ch.it4user.fintube.integration;

import ch.it4user.fintube.core.Database;
import ch.it4user.fintube.media.BackgroundFillService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

  final Database db;
  final JellyfinSyncService jellyfin;
  final BackgroundFillService filler;
  final ObjectMapper json = new ObjectMapper();
  final HttpClient http = HttpClient.newHttpClient();
  private final ConcurrentHashMap<String, Object> channelLocks = new ConcurrentHashMap<>();

  public YouTubeSyncService(Database db, JellyfinSyncService jellyfin, BackgroundFillService filler) {
    this.db = db;
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
    Map<String, String> settings = db.settings(false);
    String key = settings.get("youtube_api_key");
    if (key == null || key.isBlank()) throw new IllegalStateException("YouTube API key is not configured");

    String attemptAt = Database.now();
    markChannelAttempt(channel, attemptAt);
    try {
      String cursor = channelCursor(channel);
      List<JsonNode> searchItems = discover(channel, key, cursor, settings);
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

      String successAt = Database.now();
      updateChannelSuccess(channel, successAt, newestPublished);
      markSubscriptionsSynced(channel, successAt);
      linkEnabledSubscribers(channel);
      prefetchNewest(channel, settings);
      return count;
    } catch (Exception failure) {
      markChannelFailure(channel, Database.now(), safeError(failure));
      throw failure;
    }
  }

  /** Enqueue globally shared, low-priority cache fills for a channel's latest X videos. */
  private void prefetchNewest(String channel, Map<String, String> settings) {
    int limit;
    try { limit = Math.max(0, Math.min(1000, Integer.parseInt(settings.getOrDefault("newest_videos_to_download", "0")))); }
    catch (NumberFormatException ignored) { return; }
    if (limit == 0) return;
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "SELECT video_id FROM videos WHERE channel_id=? AND availability='AVAILABLE' ORDER BY published_at DESC LIMIT ?")) {
      p.setString(1, channel); p.setInt(2, limit);
      try (ResultSet r = p.executeQuery()) { while (r.next()) filler.enqueue(r.getString(1)); }
    } catch (Exception ignored) {
      // Prefetch must never fail metadata synchronization or interactive playback.
    }
  }

  /**
   * Remove one user's subscription and only that user's generated library
   * records/files. Canonical metadata and shared media cache are retained.
   */
  public boolean removeSubscription(long userId, long subscriptionId) throws Exception {
    List<Path> paths = new ArrayList<>();
    Path userRoot;
    boolean deleted;
    try (Connection c = db.open()) {
      c.setAutoCommit(false);
      try {
        String channel;
        String slug;
        try (PreparedStatement p = c.prepareStatement(
            "SELECT s.channel_id,u.filesystem_slug FROM youtube_subscriptions s "
                + "JOIN users u ON u.id=s.user_id WHERE s.id=? AND s.user_id=?")) {
          p.setLong(1, subscriptionId);
          p.setLong(2, userId);
          try (ResultSet r = p.executeQuery()) {
            if (!r.next()) {
              c.rollback();
              return false;
            }
            channel = r.getString(1);
            slug = r.getString(2);
          }
        }
        userRoot = db.usersRoot.resolve(slug).toAbsolutePath().normalize();
        try (PreparedStatement p = c.prepareStatement(
            "SELECT uv.library_path FROM user_videos uv JOIN videos v ON v.video_id=uv.video_id "
                + "WHERE uv.user_id=? AND v.channel_id=?")) {
          p.setLong(1, userId);
          p.setString(2, channel);
          try (ResultSet r = p.executeQuery()) {
            while (r.next()) {
              Path candidate = Path.of(r.getString(1)).toAbsolutePath().normalize();
              if (candidate.startsWith(userRoot) && !candidate.equals(userRoot)) paths.add(candidate);
            }
          }
        }
        try (PreparedStatement p = c.prepareStatement(
            "DELETE FROM user_videos WHERE user_id=? AND video_id IN "
                + "(SELECT video_id FROM videos WHERE channel_id=?)")) {
          p.setLong(1, userId);
          p.setString(2, channel);
          p.executeUpdate();
        }
        try (PreparedStatement p = c.prepareStatement(
            "DELETE FROM youtube_subscriptions WHERE id=? AND user_id=?")) {
          p.setLong(1, subscriptionId);
          p.setLong(2, userId);
          deleted = p.executeUpdate() == 1;
        }
        if (deleted) c.commit(); else c.rollback();
      } catch (Exception e) {
        try { c.rollback(); } catch (SQLException ignored) { }
        throw e;
      } finally {
        try { c.setAutoCommit(true); } catch (SQLException ignored) { }
      }
    }
    if (deleted) {
      // Delete after commit so filesystem errors cannot leave database locks.
      for (Path path : paths) deleteTreeSafely(path, userRoot);
      for (Path path : paths) deleteIfEmpty(path.getParent(), userRoot);
    }
    return deleted;
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

  private String channelCursor(String channel) throws SQLException {
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "SELECT last_sync_published_at FROM youtube_channels WHERE channel_id=?")) {
      p.setString(1, channel);
      try (ResultSet r = p.executeQuery()) {
        if (!r.next()) throw new IllegalArgumentException("unknown YouTube channel " + channel);
        return r.getString(1);
      }
    }
  }

  private void markChannelAttempt(String channel, String at) throws SQLException {
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "UPDATE youtube_channels SET last_sync_at=?,sync_error=NULL WHERE channel_id=?")) {
      p.setString(1, at);
      p.setString(2, channel);
      if (p.executeUpdate() == 0) throw new IllegalArgumentException("unknown YouTube channel " + channel);
    }
  }

  private void updateChannelSuccess(String channel, String at, String cursor) throws SQLException {
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "UPDATE youtube_channels SET last_sync_at=?,last_successful_sync_at=?,last_sync_published_at=?,sync_error=NULL WHERE channel_id=?")) {
      p.setString(1, at);
      p.setString(2, at);
      if (cursor == null || cursor.isBlank()) p.setNull(3, java.sql.Types.VARCHAR); else p.setString(3, cursor);
      p.setString(4, channel);
      p.executeUpdate();
    }
  }

  private void markChannelFailure(String channel, String at, String error) {
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "UPDATE youtube_channels SET last_sync_at=?,sync_error=? WHERE channel_id=?")) {
      p.setString(1, at);
      p.setString(2, error);
      p.setString(3, channel);
      p.executeUpdate();
    } catch (Exception ignored) { }
  }

  private void markSubscriptionsSynced(String channel, String at) throws SQLException {
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "UPDATE youtube_subscriptions SET last_checked_at=?,last_successful_sync_at=? WHERE channel_id=? AND enabled=1")) {
      p.setString(1, at);
      p.setString(2, at);
      p.setString(3, channel);
      p.executeUpdate();
    }
  }

  private void linkEnabledSubscribers(String channel) throws Exception {
    List<Long> users = new ArrayList<>();
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "SELECT user_id FROM youtube_subscriptions WHERE channel_id=? AND enabled=1")) {
      p.setString(1, channel);
      try (ResultSet r = p.executeQuery()) { while (r.next()) users.add(r.getLong(1)); }
    }
    for (long user : users) linkExistingVideos(channel, user);
  }

  private void linkExistingVideos(String channel, long user) throws Exception {
    List<String> videos = new ArrayList<>();
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "SELECT video_id FROM videos WHERE channel_id=? ORDER BY published_at DESC")) {
      p.setString(1, channel);
      try (ResultSet r = p.executeQuery()) { while (r.next()) videos.add(r.getString(1)); }
    }
    for (String video : videos) linkStoredVideo(user, video);
  }

  private void upsertVideo(JsonNode video, String channel) throws Exception {
    String id = video.path("id").asText();
    JsonNode snippet = video.path("snippet");
    int duration = duration(video.path("contentDetails").path("duration").asText());
    String availability = availability(video.path("status"));
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "INSERT INTO videos(video_id,channel_id,title,description,published_at,duration_seconds,is_short,thumbnail_url,availability,metadata_updated_at) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(video_id) DO UPDATE SET channel_id=excluded.channel_id,title=excluded.title,description=excluded.description,published_at=excluded.published_at,duration_seconds=excluded.duration_seconds,is_short=excluded.is_short,thumbnail_url=excluded.thumbnail_url,availability=excluded.availability,metadata_updated_at=excluded.metadata_updated_at")) {
      p.setString(1, id);
      p.setString(2, channel);
      p.setString(3, snippet.path("title").asText(""));
      p.setString(4, snippet.path("description").asText(""));
      p.setString(5, snippet.path("publishedAt").asText(""));
      p.setInt(6, duration);
      p.setInt(7, isShort(video, duration) ? 1 : 0);
      p.setString(8, thumbnail(snippet));
      p.setString(9, availability);
      p.setString(10, Database.now());
      p.executeUpdate();
    }
  }

  private void markMissingAsUnavailable(String channel, List<String> ids, Set<String> returned) throws SQLException {
    if (ids.isEmpty()) return;
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "UPDATE videos SET availability='UNAVAILABLE',metadata_updated_at=? WHERE video_id=? AND channel_id=?")) {
      for (String id : ids) {
        if (returned.contains(id)) continue;
        p.setString(1, Database.now());
        p.setString(2, id);
        p.setString(3, channel);
        p.addBatch();
      }
      p.executeBatch();
    }
  }

  private List<String> staleVideoIds(String channel) throws SQLException {
    String cutoff = Instant.now().minusSeconds(24 * 60 * 60L).toString();
    List<String> ids = new ArrayList<>();
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "SELECT video_id FROM videos WHERE channel_id=? AND (metadata_updated_at IS NULL OR metadata_updated_at<?) "
            + "ORDER BY metadata_updated_at LIMIT 50")) {
      p.setString(1, channel);
      p.setString(2, cutoff);
      try (ResultSet r = p.executeQuery()) { while (r.next()) ids.add(r.getString(1)); }
    }
    return ids;
  }

  /** Link one canonical metadata row into one user's isolated Jellyfin tree. */
  private void linkStoredVideo(long user, String video) throws Exception {
    String channel;
    String title;
    String description;
    String published;
    int duration;
    String thumbnail;
    String availability;
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "SELECT v.channel_id,v.title,v.description,v.published_at,v.duration_seconds,v.thumbnail_url,v.availability "
            + "FROM videos v JOIN youtube_subscriptions s ON s.channel_id=v.channel_id "
            + "WHERE v.video_id=? AND s.user_id=? AND s.enabled=1")) {
      p.setString(1, video);
      p.setLong(2, user);
      try (ResultSet r = p.executeQuery()) {
        if (!r.next()) return;
        channel = r.getString(1);
        title = r.getString(2);
        description = r.getString(3);
        published = r.getString(4);
        duration = r.getInt(5);
        thumbnail = r.getString(6);
        availability = r.getString(7);
      }
    }
    String name;
    String slug;
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "SELECT u.filesystem_slug,c.name FROM users u JOIN youtube_subscriptions s ON s.user_id=u.id "
            + "JOIN youtube_channels c ON c.channel_id=s.channel_id WHERE u.id=? AND s.channel_id=? AND s.enabled=1")) {
      p.setLong(1, user);
      p.setString(2, channel);
      try (ResultSet r = p.executeQuery()) {
        if (!r.next()) return;
        slug = r.getString(1);
        name = r.getString(2);
      }
    }
    writeLibrary(user, slug, name, channel, video, title, description, published, duration, thumbnail, availability);
  }

  // Compatibility wrapper for callers from older builds.
  void linkLibrary(long user, JsonNode video) throws Exception {
    String id = video.path("id").asText();
    if (!id.isBlank()) linkStoredVideo(user, id);
  }

  private void writeLibrary(long user, String slug, String channelName, String channel, String video,
                            String title, String description, String published, int duration,
                            String thumbnail, String availability) throws Exception {
    Path root = db.usersRoot.resolve(slug).toAbsolutePath().normalize();
    if (!root.startsWith(db.usersRoot.toAbsolutePath().normalize()))
      throw new SecurityException("invalid user filesystem root");
    Path path = root.resolve(clean(channelName) + " [" + clean(channel) + "]").resolve(video).normalize();
    if (!path.startsWith(root) || path.equals(root)) throw new SecurityException("invalid library path");
    Files.createDirectories(path);
    String token;
    try (Connection c = db.open(); PreparedStatement q = c.prepareStatement(
        "SELECT playback_token FROM user_videos WHERE user_id=? AND video_id=?")) {
      q.setLong(1, user);
      q.setString(2, video);
      try (ResultSet r = q.executeQuery()) {
        token = r.next() ? r.getString(1) : UUID.randomUUID().toString().replace("-", "");
      }
    }
    String base = db.settings(false).getOrDefault("public_base_url", "http://localhost:8080");
    Files.writeString(path.resolve("video.strm"), base + "/play/" + video + "?token=" + token + "\n");
    String date = published == null ? "" : published.length() >= 10 ? published.substring(0, 10) : published;
    String nfo = "<movie><title>" + xml(title) + "</title><plot>" + xml(description) + "</plot><studio>"
        + xml(channelName) + "</studio><runtime>" + Math.max(1, duration / 60)
        + "</runtime><uniqueid type=\"youtube\">" + xml(video) + "</uniqueid><premiered>" + xml(date)
        + "</premiered><tagline>" + xml(availability) + "</tagline></movie>";
    Files.writeString(path.resolve("video.nfo"), nfo);
    downloadThumbnail(thumbnail, path.resolve("video-thumb.jpg"));
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "INSERT INTO user_videos(user_id,video_id,library_path,playback_token,created_at) VALUES(?,?,?,?,?) "
            + "ON CONFLICT(user_id,video_id) DO UPDATE SET library_path=excluded.library_path")) {
      p.setLong(1, user);
      p.setString(2, video);
      p.setString(3, path.toString());
      p.setString(4, token);
      p.setString(5, Database.now());
      p.executeUpdate();
    }
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
