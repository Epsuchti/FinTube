package ch.it4user.fintube.service;

import ch.it4user.fintube.api.contract.model.AddSubscriptionRequest;
import ch.it4user.fintube.api.contract.model.RefreshResult;
import ch.it4user.fintube.api.contract.model.Subscription;
import ch.it4user.fintube.api.contract.model.ToggleSubscriptionRequest;
import ch.it4user.fintube.api.contract.model.Video;
import ch.it4user.fintube.core.AuditLogger;
import ch.it4user.fintube.core.ApplicationClock;
import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.integration.YouTubeSyncService;
import ch.it4user.fintube.persistence.repositories.LibraryVideoRepository;
import ch.it4user.fintube.persistence.entities.YouTubeChannelEntity;
import ch.it4user.fintube.persistence.repositories.YouTubeChannelRepository;
import ch.it4user.fintube.persistence.entities.YouTubeSubscriptionEntity;
import ch.it4user.fintube.persistence.repositories.YouTubeSubscriptionRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LibraryApplicationService {
    private final SettingsService settings;
    private final YouTubeChannelRepository channels;
    private final YouTubeSubscriptionRepository subscriptions;
    private final LibraryVideoRepository videos;
    private final AuthorizationService authorization;
    private final YouTubeSyncService sync;
    private final AuditLogger audit;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    public LibraryApplicationService(SettingsService settings,
                                     YouTubeChannelRepository channels,
                                     YouTubeSubscriptionRepository subscriptions,
                                     LibraryVideoRepository videos,
                                     AuthorizationService authorization,
                                     YouTubeSyncService sync,
                                     AuditLogger audit) {
        this.settings = settings;
        this.channels = channels;
        this.subscriptions = subscriptions;
        this.videos = videos;
        this.authorization = authorization;
        this.sync = sync;
        this.audit = audit;
    }

    public List<Subscription> subscriptions(HttpServletRequest request) {
        return database(() -> {
            AuthService.Principal principal = authorization.requireUser(request);
            return subscriptions.findForUser(principal.id()).stream().map(this::subscription).toList();
        });
    }

    @Transactional
    public void addSubscription(HttpServletRequest request, AddSubscriptionRequest requestBody) {
        database(() -> {
            AuthService.Principal principal = authorization.requireUser(request);
            Channel channel = resolve(required(requestBody.getChannel(), "channel"));
            YouTubeChannelEntity canonical = channels.findById(channel.id())
                    .orElseGet(() -> new YouTubeChannelEntity(channel.id(), channel.name(), channel.url(), ApplicationClock.now()));
            canonical.setName(channel.name());
            canonical.setUrl(channel.url());
            canonical.setUpdatedAt(ApplicationClock.now());
            channels.save(canonical);
            subscriptions.save(new YouTubeSubscriptionEntity(principal.id(), channel.id(), 1, ApplicationClock.now()));
            audit.event("SUBSCRIPTION_ADDED", Map.of("userId", principal.id(), "channelId", channel.id()));
            return null;
        });
    }

    @Transactional
    public void updateSubscription(HttpServletRequest request, long id, ToggleSubscriptionRequest requestBody) {
        database(() -> {
            AuthService.Principal principal = authorization.requireUser(request);
            YouTubeSubscriptionEntity subscription = subscriptions.findByIdAndUserId(id, principal.id())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            subscription.setEnabled(Boolean.TRUE.equals(requestBody.getEnabled()) ? 1 : 0);
            subscriptions.save(subscription);
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
            String channel = subscriptions.findByIdAndUserId(id, principal.id())
                    .filter(value -> value.getEnabled() == 1)
                    .map(YouTubeSubscriptionEntity::getChannelId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
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
            List<LibraryVideoRepository.VideoView> items = videos.findForUser(principal.id());
            long retention = Long.parseLong(settings.value("cache_retention_days") == null
                    ? "30" : settings.value("cache_retention_days"));
            return items.stream().map(item -> video(item, retention)).toList();
        });
    }

    private Subscription subscription(YouTubeSubscriptionRepository.UserSubscriptionView value) {
        return new Subscription(value.getId(), value.getChannelId(), value.getName(), booleanValue(value.getEnabled()))
                .url(value.getUrl())
                .lastCheckedAt(value.getLastCheckedAt())
                .lastSuccessfulSyncAt(value.getLastSuccessfulSyncAt());
    }

    private Video video(LibraryVideoRepository.VideoView value, long retention) {
        String accessed = value.getCacheLastAccessedAt();
        String expires = accessed == null ? null : Instant.parse(accessed).plus(Duration.ofDays(retention)).toString();
        return new Video(value.getVideoId(), value.getTitle())
                .description(value.getDescription())
                .publishedAt(value.getPublishedAt())
                .durationSeconds(value.getDurationSeconds())
                .channel(value.getChannel())
                .libraryPath(value.getLibraryPath())
                .cacheStatus(value.getCacheStatus())
                .cacheLastAccessedAt(accessed)
                .cacheExpiresAt(expires)
                .cacheBytes(value.getCacheBytes())
                .cachedFragments(value.getCachedFragments())
                .downloaded(booleanValue(value.getDownloaded()));
    }

    private Channel resolve(String raw) throws Exception {
        String id = raw.matches("UC[A-Za-z0-9_-]{20,}") ? raw : null;
        String key = settings.value("youtube_api_key");
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

    private static Boolean booleanValue(Object item) { return item instanceof Boolean b ? b : item instanceof Number n ? n.intValue() == 1 : item == null ? null : Boolean.parseBoolean(item.toString()); }

    private record Channel(String id, String name, String url) {}
}
