package ch.it4user.fintube.service;

import ch.it4user.fintube.api.contract.model.AddSubscriptionRequest;
import ch.it4user.fintube.api.contract.model.RefreshResult;
import ch.it4user.fintube.api.contract.model.Subscription;
import ch.it4user.fintube.api.contract.model.ToggleSubscriptionRequest;
import ch.it4user.fintube.api.contract.model.Video;
import ch.it4user.fintube.core.AuditLogger;
import ch.it4user.fintube.core.Database;
import ch.it4user.fintube.integration.YouTubeSyncService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

@Service
public class LibraryApplicationService {
    private final Database db;
    private final AuthorizationService authorization;
    private final YouTubeSyncService sync;
    private final AuditLogger audit;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    public LibraryApplicationService(Database db, AuthorizationService authorization, YouTubeSyncService sync, AuditLogger audit) {
        this.db = db;
        this.authorization = authorization;
        this.sync = sync;
        this.audit = audit;
    }

    public List<Subscription> subscriptions(HttpServletRequest request) {
        return database(() -> {
            AuthService.Principal principal = authorization.requireUser(request);
            return rows("SELECT s.id,c.channel_id,c.name,c.url,s.enabled,s.last_checked_at,s.last_successful_sync_at FROM youtube_subscriptions s JOIN youtube_channels c ON c.channel_id=s.channel_id WHERE s.user_id=? ORDER BY c.name", principal.id())
                    .stream().map(this::subscription).toList();
        });
    }

    public void addSubscription(HttpServletRequest request, AddSubscriptionRequest requestBody) {
        database(() -> {
            AuthService.Principal principal = authorization.requireUser(request);
            Channel channel = resolve(required(requestBody.getChannel(), "channel"));
            try (Connection connection = db.open(); PreparedStatement canonical = connection.prepareStatement(
                    "MERGE INTO youtube_channels(channel_id,name,url,updated_at) KEY(channel_id) VALUES(?,?,?,?)")) {
                canonical.setString(1, channel.id());
                canonical.setString(2, channel.name());
                canonical.setString(3, channel.url());
                canonical.setString(4, Database.now());
                canonical.executeUpdate();
                try (PreparedStatement subscription = connection.prepareStatement(
                        "INSERT INTO youtube_subscriptions(user_id,channel_id,created_at) VALUES(?,?,?)")) {
                    subscription.setLong(1, principal.id());
                    subscription.setString(2, channel.id());
                    subscription.setString(3, Database.now());
                    subscription.executeUpdate();
                }
            }
            audit.event("SUBSCRIPTION_ADDED", Map.of("userId", principal.id(), "channelId", channel.id()));
            return null;
        });
    }

    public void updateSubscription(HttpServletRequest request, long id, ToggleSubscriptionRequest requestBody) {
        database(() -> {
            AuthService.Principal principal = authorization.requireUser(request);
            try (Connection connection = db.open(); PreparedStatement statement = connection.prepareStatement(
                    "UPDATE youtube_subscriptions SET enabled=? WHERE id=? AND user_id=?")) {
                statement.setInt(1, Boolean.TRUE.equals(requestBody.getEnabled()) ? 1 : 0);
                statement.setLong(2, id);
                statement.setLong(3, principal.id());
                if (statement.executeUpdate() == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            audit.event("SUBSCRIPTION_UPDATED", Map.of("userId", principal.id(), "subscriptionId", id, "enabled", Boolean.TRUE.equals(requestBody.getEnabled())));
            return null;
        });
    }

    public void removeSubscription(HttpServletRequest request, long id) {
        database(() -> {
            AuthService.Principal principal = authorization.requireUser(request);
            if (!sync.removeSubscription(principal.id(), id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            audit.event("SUBSCRIPTION_REMOVED", Map.of("userId", principal.id(), "subscriptionId", id));
            return null;
        });
    }

    public RefreshResult refreshSubscription(HttpServletRequest request, long id) {
        return database(() -> {
            AuthService.Principal principal = authorization.requireUser(request);
            String channel;
            try (Connection connection = db.open(); PreparedStatement statement = connection.prepareStatement(
                    "SELECT channel_id FROM youtube_subscriptions WHERE id=? AND user_id=? AND enabled=1")) {
                statement.setLong(1, id);
                statement.setLong(2, principal.id());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    channel = result.getString(1);
                }
            }
            audit.event("SUBSCRIPTION_REFRESH_STARTED", Map.of("userId", principal.id(), "subscriptionId", id, "channelId", channel));
            int discovered;
            try {
                discovered = sync.sync(channel, principal.id());
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "subscription refresh failed", e);
            }
            audit.event("SUBSCRIPTION_REFRESH_COMPLETED", Map.of("userId", principal.id(), "subscriptionId", id, "channelId", channel, "discovered", discovered));
            return new RefreshResult().discovered(discovered);
        });
    }

    public List<Video> videos(HttpServletRequest request) {
        return database(() -> {
            AuthService.Principal principal = authorization.requireUser(request);
            List<Map<String, Object>> items = rows("SELECT v.video_id,v.title,v.description,v.published_at,v.duration_seconds,c.name AS channel,uv.library_path,COALESCE(e.status,'NOT_CACHED') AS cache_status,e.last_accessed_at AS cache_last_accessed_at,COALESCE((SELECT SUM(f.size_bytes) FROM cached_fragments f WHERE f.video_id=v.video_id),0) AS cache_bytes,COALESCE((SELECT COUNT(*) FROM cached_fragments f WHERE f.video_id=v.video_id AND f.completed=1),0) AS cached_fragments,CASE WHEN e.status='COMPLETE' THEN 1 ELSE 0 END AS downloaded FROM user_videos uv JOIN videos v ON v.video_id=uv.video_id JOIN youtube_channels c ON c.channel_id=v.channel_id LEFT JOIN cache_entries e ON e.video_id=v.video_id WHERE uv.user_id=? ORDER BY v.published_at DESC", principal.id());
            long retention = Long.parseLong(db.settings(false).getOrDefault("cache_retention_days", "30"));
            for (Map<String, Object> item : items) {
                Object accessed = item.get("cache_last_accessed_at");
                if (accessed != null) item.put("cache_expires_at", Instant.parse(accessed.toString()).plus(Duration.ofDays(retention)).toString());
            }
            return items.stream().map(this::video).toList();
        });
    }

    private Subscription subscription(Map<String, Object> value) {
        return new Subscription(number(value, "id"), text(value, "channel_id"), text(value, "name"), booleanValue(value.get("enabled")))
                .url(text(value, "url"))
                .lastCheckedAt(text(value, "last_checked_at"))
                .lastSuccessfulSyncAt(text(value, "last_successful_sync_at"));
    }

    private Video video(Map<String, Object> value) {
        return new Video(text(value, "video_id"), text(value, "title"))
                .description(text(value, "description"))
                .publishedAt(text(value, "published_at"))
                .durationSeconds(integer(value, "duration_seconds"))
                .channel(text(value, "channel"))
                .libraryPath(text(value, "library_path"))
                .cacheStatus(text(value, "cache_status"))
                .cacheLastAccessedAt(text(value, "cache_last_accessed_at"))
                .cacheExpiresAt(text(value, "cache_expires_at"))
                .cacheBytes(longValue(value, "cache_bytes"))
                .cachedFragments(integer(value, "cached_fragments"))
                .downloaded(booleanValue(value.get("downloaded")));
    }

    private Channel resolve(String raw) throws Exception {
        String id = raw.matches("UC[A-Za-z0-9_-]{20,}") ? raw : null;
        String key = db.settings(false).get("youtube_api_key");
        if (key == null || key.isBlank()) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "administrator must configure YouTube Data API key");
        String endpoint = id != null
                ? "https://www.googleapis.com/youtube/v3/channels?part=snippet&id=" + id + "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                : "https://www.googleapis.com/youtube/v3/search?part=snippet&type=channel&maxResults=1&q=" + URLEncoder.encode(raw, StandardCharsets.UTF_8) + "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(http.send(HttpRequest.newBuilder(URI.create(endpoint)).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        if (!root.path("items").isArray() || root.path("items").isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "channel not found");
        JsonNode item = root.path("items").get(0);
        String channelId = id != null ? item.path("id").asText() : item.path("id").path("channelId").asText();
        return new Channel(channelId, item.path("snippet").path("title").asText(), "https://www.youtube.com/channel/" + channelId);
    }

    private List<Map<String, Object>> rows(String sql, Object... args) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = db.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            try (ResultSet rows = statement.executeQuery()) {
                var metadata = rows.getMetaData();
                while (rows.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) row.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT), rows.getObject(i));
                    result.add(row);
                }
            }
        }
        return result;
    }

    private <T> T database(Callable<T> operation) {
        try {
            return operation.call();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "library operation could not be completed", e);
        }
    }

    private static String required(String value, String key) {
        if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is required");
        return value;
    }

    private static String text(Map<String, Object> value, String key) { Object item = value.get(key); return item == null ? null : item.toString(); }
    private static long number(Map<String, Object> value, String key) { Object item = value.get(key); if (item == null) throw new IllegalStateException("missing numeric column " + key); return item instanceof Number n ? n.longValue() : Long.parseLong(item.toString()); }
    private static Integer integer(Map<String, Object> value, String key) { Object item = value.get(key); return item instanceof Number n ? n.intValue() : item == null ? null : Integer.valueOf(item.toString()); }
    private static Long longValue(Map<String, Object> value, String key) { Object item = value.get(key); return item instanceof Number n ? n.longValue() : item == null ? null : Long.valueOf(item.toString()); }
    private static Boolean booleanValue(Object item) { return item instanceof Boolean b ? b : item instanceof Number n ? n.intValue() == 1 : item == null ? null : Boolean.parseBoolean(item.toString()); }

    private record Channel(String id, String name, String url) {}
}
