package ch.it4user.fintube.service;

import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.media.ProxiedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only, fail-open SponsorBlock lookup for playback. */
@Service
public class SponsorBlockService {
    private static final Logger LOG = LoggerFactory.getLogger(SponsorBlockService.class);
    private static final String ENABLED = "sponsorblock_enabled";
    private static final String API_URL = "sponsorblock_api_url";
    private static final String DEFAULT_API_URL = "https://api.sponsor.ajay.app";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final int MAX_CACHE_ENTRIES = 10_000;
    private final SettingsService settings;
    private final ProxiedHttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentHashMap<String, CachedSegments> cache = new ConcurrentHashMap<>();

    public SponsorBlockService(SettingsService settings, ProxiedHttpClient http) {
        this.settings = settings;
        this.http = http;
    }

    /** Uses the four-character SHA-256 prefix endpoint, so the API never receives the exact video ID. */
    public List<Segment> skipSegments(String videoId) {
        if (!Boolean.parseBoolean(settings.value(ENABLED))) return List.of();
        String baseUrl = settings.value(API_URL);
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = DEFAULT_API_URL;
        String key = baseUrl + '\u0000' + videoId;
        CachedSegments cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.segments();
        try {
            List<Segment> segments = request(videoId, baseUrl);
            if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
            cache.put(key, new CachedSegments(segments, Instant.now().plus(CACHE_TTL)));
            return segments;
        } catch (Exception failure) {
            LOG.warn("event=SPONSORBLOCK_LOOKUP_FAILED video={} reason={}", videoId, failure.toString());
            return List.of();
        }
    }

    private List<Segment> request(String videoId, String baseUrl) throws Exception {
        String prefix = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(videoId.getBytes(StandardCharsets.UTF_8))).substring(0, 4);
        String endpoint = baseUrl.replaceAll("/+$", "") + "/api/skipSegments/" + prefix
                + "?categories=%5B%22sponsor%22%5D&actionTypes=%5B%22skip%22%5D";
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "FinTube SponsorBlock client").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) return List.of();
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("SponsorBlock returned HTTP " + response.statusCode());
        List<Segment> result = new ArrayList<>();
        for (JsonNode candidate : json.readTree(response.body())) {
            if (!videoId.equals(candidate.path("videoID").asText())) continue;
            for (JsonNode item : candidate.path("segments")) {
                JsonNode range = item.path("segment");
                if (range.size() != 2) continue;
                double start = range.get(0).asDouble(Double.NaN);
                double end = range.get(1).asDouble(Double.NaN);
                if (Double.isFinite(start) && Double.isFinite(end) && start >= 0 && end > start) result.add(new Segment(start, end));
            }
        }
        return merge(result);
    }

    private static List<Segment> merge(List<Segment> segments) {
        if (segments.isEmpty()) return List.of();
        segments.sort(Comparator.comparingDouble(Segment::startSeconds));
        List<Segment> merged = new ArrayList<>();
        for (Segment next : segments) {
            if (merged.isEmpty() || next.startSeconds() > merged.getLast().endSeconds()) merged.add(next);
            else {
                Segment previous = merged.removeLast();
                merged.add(new Segment(previous.startSeconds(), Math.max(previous.endSeconds(), next.endSeconds())));
            }
        }
        return List.copyOf(merged);
    }

    public record Segment(double startSeconds, double endSeconds) {}
    private record CachedSegments(List<Segment> segments, Instant expiresAt) {}
}
