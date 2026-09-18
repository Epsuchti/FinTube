package ch.it4user.fintube.service;

import ch.it4user.fintube.api.contract.model.AddSubscriptionRequest;
import ch.it4user.fintube.api.contract.model.ImportChannelsResult;
import ch.it4user.fintube.api.contract.model.ImportCookiesRequest;
import ch.it4user.fintube.api.contract.model.RefreshResult;
import ch.it4user.fintube.api.contract.model.Subscription;
import ch.it4user.fintube.api.contract.model.ToggleSubscriptionRequest;
import ch.it4user.fintube.api.contract.model.Video;
import ch.it4user.fintube.core.AuditLogger;
import ch.it4user.fintube.core.ApplicationClock;
import ch.it4user.fintube.core.ApplicationPaths;
import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.integration.YouTubeSyncService;
import ch.it4user.fintube.persistence.entities.UserVideoEntity;
import ch.it4user.fintube.persistence.repositories.LibraryVideoRepository;
import ch.it4user.fintube.persistence.entities.YouTubeChannelEntity;
import ch.it4user.fintube.persistence.repositories.YouTubeChannelRepository;
import ch.it4user.fintube.persistence.entities.YouTubeSubscriptionEntity;
import ch.it4user.fintube.persistence.repositories.YouTubeSubscriptionRepository;
import ch.it4user.fintube.persistence.repositories.UserVideoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LibraryApplicationService {
    private static final Logger LOG = LoggerFactory.getLogger(LibraryApplicationService.class);
    private static final int MAX_COOKIE_BYTES = 5_000_000;
    private static final int MAX_SUBSCRIPTION_FEED_ITEMS = 5_000;
    private static final Duration COOKIE_IMPORT_TIMEOUT = Duration.ofSeconds(180);
    private static final Pattern YOUTUBE_CHANNEL_ID = Pattern.compile("(?<![A-Za-z0-9_-])(UC[A-Za-z0-9_-]{20,})(?![A-Za-z0-9_-])");
    private final SettingsService settings;
    private final ApplicationPaths paths;
    private final YouTubeChannelRepository channels;
    private final YouTubeSubscriptionRepository subscriptions;
    private final UserVideoRepository userVideos;
    private final LibraryVideoRepository videos;
    private final AuthorizationService authorization;
    private final YouTubeSyncService sync;
    private final AuditLogger audit;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    public LibraryApplicationService(SettingsService settings,
                                     ApplicationPaths paths,
                                     YouTubeChannelRepository channels,
                                     YouTubeSubscriptionRepository subscriptions,
                                     UserVideoRepository userVideos,
                                     LibraryVideoRepository videos,
                                     AuthorizationService authorization,
                                     YouTubeSyncService sync,
                                     AuditLogger audit) {
        this.settings = settings;
        this.paths = paths;
        this.channels = channels;
        this.subscriptions = subscriptions;
        this.userVideos = userVideos;
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
            if (saveSubscription(principal.id(), channel)) {
                audit.event("SUBSCRIPTION_ADDED", Map.of("userId", principal.id(), "channelId", channel.id()));
            }
            return null;
        });
    }

    @Transactional
    public ImportChannelsResult importSubscriptionsFromCookies(HttpServletRequest request,
                                                                ImportCookiesRequest requestBody) {
        return database(() -> {
            AuthService.Principal principal = authorization.requireUser(request);
            String cookies = required(requestBody == null ? null : requestBody.getCookies(), "cookies");
            byte[] cookieBytes = cookies.getBytes(StandardCharsets.UTF_8);
            if (cookieBytes.length > MAX_COOKIE_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cookie file is too large");
            }
            if (!looksLikeNetscapeCookieFile(cookies)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "upload a Netscape-format YouTube cookies.txt file");
            }

            Path cookieFile = Files.createTempFile(paths.root, "youtube-import-", ".cookies.txt");
            restrictToOwner(cookieFile);
            try {
                Files.write(cookieFile, cookieBytes);
                List<Channel> discovered = discoverSubscriptionChannels(cookieFile);
                if (discovered.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "no YouTube subscription channels were found");
                }
                int imported = 0;
                int skipped = 0;
                for (Channel channel : discovered) {
                    if (saveSubscription(principal.id(), channel)) imported++;
                    else skipped++;
                }
                audit.event("SUBSCRIPTIONS_IMPORTED", Map.of(
                        "userId", principal.id(), "imported", imported, "skipped", skipped));
                return new ImportChannelsResult().imported(imported).skipped(skipped).failed(0);
            } finally {
                try {
                    Files.deleteIfExists(cookieFile);
                } catch (IOException cleanupFailure) {
                    LOG.warn("Could not remove temporary YouTube cookie file", cleanupFailure);
                }
            }
        });
    }

    @Transactional
    public void updateSubscription(HttpServletRequest request, long id, ToggleSubscriptionRequest requestBody) {
        database(() -> {
            AuthService.Principal principal = authorization.requireUser(request);
            YouTubeSubscriptionEntity subscription = subscriptions.findByIdAndUserId(id, principal.id())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            subscription.setEnabled(Boolean.TRUE.equals(requestBody.getEnabled()) ? 1 : 0);
            Integer initialImportCount = requestBody.getInitialImportCount();
            Integer downloadCount = requestBody.getDownloadCount();
            if (initialImportCount != null) {
                if (initialImportCount < 1 || initialImportCount > 1000) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "initial import count must be between 1 and 1000");
                }
                YouTubeChannelEntity channel = channels.findById(subscription.getChannelId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
                channel.setInitialImportCount(initialImportCount);
                channel.setLastSyncPublishedAt(null);
                channels.save(channel);
            }
            if (downloadCount != null) {
                if (downloadCount < 0 || downloadCount > 1000) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "download count must be between 0 and 1000");
                }
                YouTubeChannelEntity channel = channels.findById(subscription.getChannelId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
                channel.setDownloadCount(downloadCount);
                channels.save(channel);
            }
            subscriptions.save(subscription);
            Map<String, Object> auditFields = new java.util.LinkedHashMap<>();
            auditFields.put("userId", principal.id());
            auditFields.put("subscriptionId", id);
            auditFields.put("enabled", Boolean.TRUE.equals(requestBody.getEnabled()));
            if (initialImportCount != null) auditFields.put("initialImportCount", initialImportCount);
            if (downloadCount != null) auditFields.put("downloadCount", downloadCount);
            audit.event("SUBSCRIPTION_UPDATED", auditFields);
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
                LOG.error("Subscription refresh failed for subscriptionId={} channelId={}", id, channel, e);
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

    public Resource thumbnail(HttpServletRequest request, String video) {
        return database(() -> {
            AuthService.Principal principal = authorization.requireUser(request);
            UserVideoEntity link = userVideos.findByIdUserIdAndIdVideoId(principal.id(), video)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            Path userRoot = paths.usersRoot.resolve(principal.slug()).toAbsolutePath().normalize();
            Path libraryPath = Path.of(link.getLibraryPath()).toAbsolutePath().normalize();
            if (!libraryPath.startsWith(userRoot)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            Path thumbnail = libraryPath.resolve("video-thumb.jpg").normalize();
            if (!thumbnail.startsWith(userRoot) || !Files.isRegularFile(thumbnail)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return new FileSystemResource(thumbnail);
        });
    }

    private Subscription subscription(YouTubeSubscriptionRepository.UserSubscriptionView value) {
        int importCount = value.getInitialImportCount() == null ? initialImportCount() : value.getInitialImportCount();
        int downloadCount = value.getDownloadCount() == null ? 0 : value.getDownloadCount();
        return new Subscription(value.getId(), value.getChannelId(), value.getName(), booleanValue(value.getEnabled()), importCount, downloadCount)
                .url(value.getUrl())
                .lastCheckedAt(value.getLastCheckedAt())
                .lastSuccessfulSyncAt(value.getLastSuccessfulSyncAt());
    }

    private int initialImportCount() {
        try {
            return Math.max(1, Math.min(1000, Integer.parseInt(settings.value("initial_channel_import_count"))));
        } catch (RuntimeException ignored) {
            return 20;
        }
    }

    private boolean saveSubscription(long userId, Channel channel) {
        YouTubeChannelEntity canonical = channels.findById(channel.id())
                .orElseGet(() -> new YouTubeChannelEntity(channel.id(), channel.name(), channel.url(), ApplicationClock.now()));
        canonical.setName(channel.name());
        canonical.setUrl(channel.url());
        canonical.setUpdatedAt(ApplicationClock.now());
        if (canonical.getInitialImportCount() == null) canonical.setInitialImportCount(initialImportCount());
        if (canonical.getDownloadCount() == null) canonical.setDownloadCount(0);
        channels.save(canonical);
        if (subscriptions.findByUserIdAndChannelId(userId, channel.id()).isPresent()) return false;
        subscriptions.save(new YouTubeSubscriptionEntity(userId, channel.id(), 1, ApplicationClock.now()));
        return true;
    }

    private List<Channel> discoverSubscriptionChannels(Path cookieFile) throws Exception {
        String executable = settings.value("yt_dlp_path");
        if (executable == null || executable.isBlank()) executable = "yt-dlp";
        List<String> command = new ArrayList<>(List.of(
                executable, "--ignore-config", "--flat-playlist", "--dump-single-json", "--skip-download",
                "--no-warnings", "--playlist-end", Integer.toString(MAX_SUBSCRIPTION_FEED_ITEMS),
                "--cookies", cookieFile.toString(), ":ytsubs"));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "yt-dlp executable is unavailable; configure yt_dlp_path or install yt-dlp", failure);
        }
        CompletableFuture<byte[]> output = readProcessStream(process.getInputStream());
        CompletableFuture<byte[]> errors = readProcessStream(process.getErrorStream());
        if (!process.waitFor(COOKIE_IMPORT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "YouTube subscription import timed out");
        }
        int exitCode = process.exitValue();
        byte[] outputBytes = output.join();
        errors.join();
        if (exitCode != 0) {
            LOG.warn("YouTube subscription import failed with yt-dlp exit code {}", exitCode);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "yt-dlp could not read the YouTube subscriptions feed");
        }
        JsonNode root = objectMapper.readTree(new String(outputBytes, StandardCharsets.UTF_8));
        return channelsFromFeed(root);
    }

    private CompletableFuture<byte[]> readProcessStream(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return stream.readAllBytes();
            } catch (IOException failure) {
                throw new RuntimeException(failure);
            }
        });
    }

    private List<Channel> channelsFromFeed(JsonNode root) {
        List<Channel> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (root == null || !root.path("entries").isArray()) return result;
        for (JsonNode entry : root.path("entries")) {
            String channelId = entry.path("channel_id").asText("");
            if (channelId.isBlank()) channelId = channelId(entry.path("channel_url").asText(""));
            if (channelId.isBlank() || !YOUTUBE_CHANNEL_ID.matcher(channelId).matches() || !seen.add(channelId)) continue;
            String name = entry.path("channel").asText("");
            if (name.isBlank()) name = entry.path("uploader").asText("");
            if (name.isBlank()) name = channelId;
            result.add(new Channel(channelId, name,
                    "https://www.youtube.com/channel/" + channelId));
        }
        return result;
    }

    private static boolean looksLikeNetscapeCookieFile(String value) {
        String normalized = value.startsWith("\uFEFF") ? value.substring(1) : value;
        return normalized.lines().anyMatch(line -> line.startsWith("# Netscape HTTP Cookie File")
                || line.startsWith("# HTTP Cookie File"));
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private Video video(LibraryVideoRepository.VideoView value, long retention) {
        String accessed = value.getCacheLastAccessedAt();
        String expires = accessed == null ? null : Instant.parse(accessed).plus(Duration.ofDays(retention)).toString();
        return new Video(value.getVideoId(), value.getTitle())
                .description(value.getDescription())
                .publishedAt(value.getPublishedAt())
                .durationSeconds(value.getDurationSeconds())
                .channel(value.getChannel())
                .thumbnailUrl("/api/videos/" + value.getVideoId() + "/thumbnail")
                .libraryPath(value.getLibraryPath())
                .cacheStatus(value.getCacheStatus())
                .cacheLastAccessedAt(accessed)
                .cacheExpiresAt(expires)
                .cacheBytes(value.getCacheBytes())
                .cachedFragments(value.getCachedFragments())
                .downloaded(booleanValue(value.getDownloaded()));
    }

    private Channel resolve(String raw) throws Exception {
        String id = channelId(raw);
        String key = settings.value("youtube_api_key");
        if (key == null || key.isBlank()) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "administrator must configure YouTube Data API key");
        String endpoint = !id.isBlank()
                ? "https://www.googleapis.com/youtube/v3/channels?part=snippet&id=" + id + "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                : "https://www.googleapis.com/youtube/v3/search?part=snippet&type=channel&maxResults=1&q=" + URLEncoder.encode(raw, StandardCharsets.UTF_8) + "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(http.send(HttpRequest.newBuilder(URI.create(endpoint)).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        if (!root.path("items").isArray() || root.path("items").isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "channel not found");
        JsonNode item = root.path("items").get(0);
        String channelId = !id.isBlank() ? item.path("id").asText() : item.path("id").path("channelId").asText();
        return new Channel(channelId, item.path("snippet").path("title").asText(), "https://www.youtube.com/channel/" + channelId);
    }

    private static String channelId(String value) {
        Matcher matcher = YOUTUBE_CHANNEL_ID.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private <T> T database(Callable<T> operation) {
        try {
            return operation.call();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Library operation failed", e);
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
