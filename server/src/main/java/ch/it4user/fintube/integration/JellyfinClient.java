package ch.it4user.fintube.integration;

import ch.it4user.fintube.core.Database;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Small, deliberately isolated Jellyfin REST client.
 *
 * Jellyfin is an optional integration.  Every method re-reads the persisted
 * settings, so changing the endpoint or API key in the admin UI takes effect
 * without restarting FinTube.  The API key is only sent in a header and is
 * never included in an exception or result returned to the web layer.
 */
@Service
public class JellyfinClient {
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
  private static final long TICKS_PER_SECOND = 10_000_000L;

  final Database db;
  final ObjectMapper json;
  final HttpClient http;

  @Autowired
  public JellyfinClient(Database db) {
    this(db, new ObjectMapper(), HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build());
  }

  JellyfinClient(Database db, ObjectMapper json, HttpClient http) {
    this.db = db;
    this.json = json;
    this.http = http;
  }

  /** Values are intentionally safe to expose in an admin status response. */
  public record Configuration(boolean enabled, boolean configured, boolean autoRefresh,
                              boolean runtimeSync, String baseUrl, boolean apiKeyConfigured,
                              Duration requestTimeout) {}

  public record Status(boolean enabled, boolean configured, boolean reachable,
                       boolean apiKeyConfigured, int statusCode, String baseUrl,
                       String serverName, String version, String message) {}

  public record Operation(boolean success, int statusCode, String message) {}

  public record Item(String id, String path, long runtimeTicks, String videoId) {}

  public Configuration configuration() {
    try {
      Map<String, String> s = db.settings(false);
      String rawUrl = s.getOrDefault("jellyfin_url", "").trim();
      String baseUrl = normalizeBaseUrl(rawUrl);
      boolean enabled = parseBoolean(s.getOrDefault("jellyfin_enabled", "false"), !baseUrl.isBlank());
      boolean autoRefresh = parseBoolean(s.getOrDefault("jellyfin_auto_refresh", "true"), true);
      boolean runtimeSync = parseBoolean(s.getOrDefault("jellyfin_runtime_sync", "true"), true);
      boolean apiKey = !s.getOrDefault("jellyfin_api_key", "").isBlank();
      long seconds;
      try {
        seconds = Math.max(1L, Math.min(120L,
            Long.parseLong(s.getOrDefault("jellyfin_request_timeout_seconds", "10"))));
      } catch (NumberFormatException e) {
        seconds = 10L;
      }
      return new Configuration(enabled, !baseUrl.isBlank(), autoRefresh, runtimeSync,
          baseUrl, apiKey, Duration.ofSeconds(seconds));
    } catch (Exception e) {
      return new Configuration(false, false, false, false, "", false, DEFAULT_TIMEOUT);
    }
  }

  /**
   * Validate connectivity and credentials.  A disabled/unconfigured bridge is
   * reported as such rather than as an application failure.
   */
  public Status status() {
    Configuration c = configuration();
    if (!c.enabled()) {
      return new Status(false, c.configured(), false, c.apiKeyConfigured(), 0,
          c.baseUrl(), "", "", c.configured() ? "Jellyfin integration is disabled" :
          "Jellyfin URL is not configured");
    }
    if (!c.configured()) {
      return new Status(true, false, false, c.apiKeyConfigured(), 0, "", "", "",
          "Jellyfin URL is not configured");
    }
    try {
      // Public info lets an administrator diagnose reachability before an API
      // key is entered; privileged operations still require the key.
      HttpResponse<String> response = request(c, "GET",
          c.apiKeyConfigured() ? "/System/Info" : "/System/Info/Public", "");
      if (response.statusCode() == 401 || response.statusCode() == 403) {
        return new Status(true, true, true, c.apiKeyConfigured(), response.statusCode(), c.baseUrl(),
            "", "", "Jellyfin rejected the API key");
      }
      if (response.statusCode() / 100 != 2) {
        return new Status(true, true, false, c.apiKeyConfigured(), response.statusCode(), c.baseUrl(),
            "", "", "Jellyfin returned HTTP " + response.statusCode());
      }
      JsonNode root = json.readTree(response.body());
      return new Status(true, true, true, c.apiKeyConfigured(), response.statusCode(), c.baseUrl(),
          root.path("ServerName").asText(""), root.path("Version").asText(""), "ok");
    } catch (IllegalArgumentException e) {
      return new Status(true, false, false, c.apiKeyConfigured(), 0, c.baseUrl(), "", "",
          "Invalid Jellyfin URL");
    } catch (Exception e) {
      return new Status(true, true, false, c.apiKeyConfigured(), 0, c.baseUrl(), "", "",
          "Jellyfin is unreachable");
    }
  }

  /** Ask Jellyfin to rescan all configured libraries. */
  public Operation refreshLibraries() {
    Configuration c = configuration();
    if (!c.enabled()) return new Operation(false, 0, "Jellyfin integration is disabled");
    if (!c.configured()) return new Operation(false, 0, "Jellyfin URL is not configured");
    try {
      HttpResponse<String> r = request(c, "POST", "/Library/Refresh", "");
      if (r.statusCode() / 100 == 2) return new Operation(true, r.statusCode(), "library refresh queued");
      return new Operation(false, r.statusCode(), "Jellyfin returned HTTP " + r.statusCode());
    } catch (Exception e) {
      return new Operation(false, 0, "Jellyfin is unreachable");
    }
  }

  /**
   * Locate a scanned item by the YouTube provider id.  Path matching is a
   * fallback for Jellyfin versions which do not index the NFO provider id.
   */
  public Optional<Item> findItem(String videoId, Path libraryPath) {
    Configuration c = configuration();
    if (!c.enabled() || !c.configured() || videoId == null || videoId.isBlank()) return Optional.empty();
    List<String> queries = new ArrayList<>();
    queries.add("/Items?Recursive=true&IncludeItemTypes=Movie&Fields=Path,ProviderIds,RunTimeTicks&Limit=100&SearchTerm=" + enc(videoId));
    if (libraryPath != null) {
      queries.add("/Items?Recursive=true&IncludeItemTypes=Movie&Fields=Path,ProviderIds,RunTimeTicks&Limit=100&Path=" + enc(libraryPath.toString()));
    }
    for (String query : queries) {
      try {
        HttpResponse<String> r = request(c, "GET", query, "");
        if (r.statusCode() / 100 != 2) continue;
        JsonNode items = json.readTree(r.body()).path("Items");
        if (!items.isArray()) continue;
        for (JsonNode item : items) {
          String providerId = providerId(item.path("ProviderIds"));
          String path = item.path("Path").asText("");
          boolean idMatch = videoId.equals(providerId);
          boolean pathMatch = libraryPath != null && sameOrChild(path, libraryPath);
          if (idMatch || pathMatch) {
            return Optional.of(new Item(item.path("Id").asText(), path,
                item.path("RunTimeTicks").asLong(0), videoId));
          }
        }
      } catch (Exception ignored) {
        // A missing/temporarily unavailable Jellyfin must not break ingestion.
      }
    }
    return Optional.empty();
  }

  /**
   * Update the runtime using Jellyfin's item metadata endpoint.  Jellyfin
   * accepts BaseItemDto updates through POST on current releases; PUT is used
   * as a compatibility fallback for older deployments exposing that verb.
   */
  public Operation updateRuntime(String itemId, long durationSeconds) {
    Configuration c = configuration();
    if (!c.enabled()) return new Operation(false, 0, "Jellyfin integration is disabled");
    if (!c.configured()) return new Operation(false, 0, "Jellyfin URL is not configured");
    if (itemId == null || itemId.isBlank()) return new Operation(false, 400, "Jellyfin item id is missing");
    long ticks;
    try {
      ticks = Math.multiplyExact(Math.max(0L, durationSeconds), TICKS_PER_SECOND);
    } catch (ArithmeticException e) {
      return new Operation(false, 400, "runtime is out of range");
    }
    try {
      // UpdateItem expects a BaseItemDto on several Jellyfin releases.  A
      // partial {Id,RunTimeTicks} body can be rejected with a server error
      // because fields such as Type/Path are used while persisting.  Fetch the
      // current DTO first and change only the runtime; retain a minimal
      // fallback for older/custom servers that do not expose item GET.
      ObjectNode body = json.createObjectNode();
      try {
        HttpResponse<String> current = request(c, "GET", "/Items/" + encPath(itemId), "");
        if (current.statusCode() / 100 == 2) {
          JsonNode node = json.readTree(current.body());
          if (node.isObject()) body = (ObjectNode) node;
        }
      } catch (Exception ignored) { }
      body.put("Id", itemId);
      body.put("RunTimeTicks", ticks);
      String payload = json.writeValueAsString(body);
      HttpResponse<String> r = request(c, "POST", "/Items/" + encPath(itemId), payload);
      if (r.statusCode() / 100 == 2) return new Operation(true, r.statusCode(), "runtime updated");
      if (r.statusCode() == 405 || r.statusCode() == 404) {
        r = request(c, "PUT", "/Items/" + encPath(itemId), payload);
        if (r.statusCode() / 100 == 2) return new Operation(true, r.statusCode(), "runtime updated");
      }
      return new Operation(false, r.statusCode(), "Jellyfin returned HTTP " + r.statusCode());
    } catch (Exception e) {
      return new Operation(false, 0, "Jellyfin is unreachable");
    }
  }

  private HttpResponse<String> request(Configuration c, String method, String path, String body)
      throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(c.baseUrl() + path))
        .timeout(c.requestTimeout())
        .header("Accept", "application/json");
    if (c.apiKeyConfigured()) b.header("X-Emby-Token", apiKey());
    if ("GET".equals(method)) b.GET();
    else if ("POST".equals(method)) b.header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
    else if ("PUT".equals(method)) b.header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
    else throw new IllegalArgumentException("unsupported HTTP method");
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String apiKey() {
    try { return db.settings(false).getOrDefault("jellyfin_api_key", ""); }
    catch (Exception e) { return ""; }
  }

  public static String normalizeBaseUrl(String raw) {
    if (raw == null || raw.isBlank()) return "";
    String url = raw.trim().replaceAll("/+$", "");
    URI uri;
    try { uri = URI.create(url); }
    catch (IllegalArgumentException e) { return ""; }
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) ||
        uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null ||
        uri.getQuery() != null) return "";
    return url;
  }

  static boolean parseBoolean(String raw, boolean fallback) {
    if (raw == null) return fallback;
    if ("true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw)) return true;
    if ("false".equalsIgnoreCase(raw) || "0".equals(raw) || "no".equalsIgnoreCase(raw)) return false;
    return fallback;
  }

  static String providerId(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return "";
    for (String key : List.of("YouTube", "Youtube", "youtube")) {
      String value = node.path(key).asText("");
      if (!value.isBlank()) return value;
    }
    return "";
  }

  static boolean sameOrChild(String candidate, Path root) {
    if (candidate == null || candidate.isBlank() || root == null) return false;
    try {
      Path p = Path.of(candidate).toAbsolutePath().normalize();
      Path r = root.toAbsolutePath().normalize();
      return p.equals(r) || p.startsWith(r);
    } catch (RuntimeException e) { return false; }
  }

  static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

  static String encPath(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
