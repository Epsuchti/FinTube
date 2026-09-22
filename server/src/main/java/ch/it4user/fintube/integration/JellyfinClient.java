package ch.it4user.fintube.integration;

import ch.it4user.fintube.core.SettingsService;
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

  final SettingsService settings;
  final ObjectMapper json;
  final HttpClient http;

  @Autowired
  public JellyfinClient(SettingsService settings) {
    this(settings, new ObjectMapper(), HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build());
  }

  JellyfinClient(SettingsService settings, ObjectMapper json, HttpClient http) {
    this.settings = settings;
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

  public record PlayedItem(String id, String path, String videoId) {}

  public record PlayedItems(boolean success, List<PlayedItem> items, String message) {}

  /** Outcome of applying a folder preference for every Jellyfin user who can see it. */
  public record SortingResult(boolean itemFound, int users, int changed, int failed, String message) {}

  public Configuration configuration() {
    try {
      Map<String, String> s = settings();
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
      HttpResponse<String> response = request(c, "GET", "/System/Info", "");
      if (response.statusCode() == 401 || response.statusCode() == 403) {
        return new Status(true, true, true, c.apiKeyConfigured(), response.statusCode(), c.baseUrl(),
            "", "", authFailureMessage(response.statusCode()));
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
      return new Operation(false, r.statusCode(), operationFailureMessage(r.statusCode()));
    } catch (Exception e) {
      return new Operation(false, 0, "Jellyfin is unreachable");
    }
  }

  /** Return movies marked played for one configured Jellyfin user (id or exact name). */
  public PlayedItems playedItems(String configuredUser) {
    Configuration c = configuration();
    if (!c.enabled()) return new PlayedItems(false, List.of(), "Jellyfin integration is disabled");
    if (!c.configured()) return new PlayedItems(false, List.of(), "Jellyfin URL is not configured");
    if (configuredUser == null || configuredUser.isBlank()) {
      return new PlayedItems(false, List.of(), "Jellyfin watched user is not configured");
    }
    try {
      String userId = resolveUserId(c, configuredUser.trim());
      if (userId.isBlank()) {
        return new PlayedItems(false, List.of(), "Jellyfin user was not found");
      }
      List<PlayedItem> result = new ArrayList<>();
      int start = 0;
      int pageSize = 200;
      while (true) {
        String query = "/Users/" + encPath(userId)
            + "/Items?Recursive=true&IsPlayed=true&IncludeItemTypes=Movie"
            + "&Fields=Path,ProviderIds&StartIndex=" + start + "&Limit=" + pageSize;
        HttpResponse<String> r = request(c, "GET", query, "");
        if (r.statusCode() / 100 != 2) {
          return new PlayedItems(false, List.of(), operationFailureMessage(r.statusCode()));
        }
        JsonNode root = json.readTree(r.body());
        JsonNode items = root.path("Items");
        if (!items.isArray()) break;
        for (JsonNode item : items) {
          result.add(new PlayedItem(item.path("Id").asText(""), item.path("Path").asText(""),
              providerId(item.path("ProviderIds"))));
        }
        int returned = items.size();
        start += returned;
        int total = root.path("TotalRecordCount").asInt(start);
        if (returned == 0 || start >= total) break;
      }
      return new PlayedItems(true, List.copyOf(result), "ok");
    } catch (Exception e) {
      return new PlayedItems(false, List.of(), "Jellyfin is unreachable");
    }
  }

  private String resolveUserId(Configuration c, String configuredUser) throws Exception {
    HttpResponse<String> r = request(c, "GET", "/Users", "");
    if (r.statusCode() / 100 != 2) return "";
    JsonNode users = json.readTree(r.body());
    if (!users.isArray()) return "";
    for (JsonNode user : users) {
      String id = user.path("Id").asText("");
      String name = user.path("Name").asText("");
      if (configuredUser.equals(id) || configuredUser.equalsIgnoreCase(name)) return id;
    }
    return "";
  }

  /**
   * Set one FinTube-managed folder's sort order for users who can access it.
   * Display preferences are replaced by Jellyfin, so this deliberately reads
   * and writes the complete DTO and changes only the three sorting fields.
   */
  public synchronized SortingResult ensureFolderSorting(Path folder, Path managedRoot,
                                                         String sortBy, String sortOrder) {
    Configuration c = configuration();
    if (!c.enabled() || !c.configured()) {
      return new SortingResult(false, 0, 0, 0, "Jellyfin integration is disabled or not configured");
    }
    if (folder == null || managedRoot == null || sortBy == null || sortBy.isBlank()
        || sortOrder == null || sortOrder.isBlank()) {
      return new SortingResult(false, 0, 0, 1, "invalid sorting request");
    }
    try {
      Optional<String> itemId = findFolderId(c, folder, managedRoot);
      if (itemId.isEmpty()) {
        return new SortingResult(false, 0, 0, 0, "folder has not been indexed by Jellyfin");
      }
      JsonNode users = users(c);
      if (!users.isArray()) return new SortingResult(true, 0, 0, 1, "Jellyfin users could not be read");
      int relevant = 0;
      int changed = 0;
      int failed = 0;
      for (JsonNode user : users) {
        if (user.path("Policy").path("IsDisabled").asBoolean(false)) continue;
        String userId = user.path("Id").asText("");
        if (userId.isBlank()) continue;
        Optional<String> preferencesId = displayPreferencesId(c, userId, itemId.get());
        if (preferencesId.isEmpty()) continue; // The user cannot see this item.
        relevant++;
        Operation result = ensureSorting(c, userId, preferencesId.get(), sortBy, sortOrder);
        if (result.success()) {
          if (result.statusCode() != 304) changed++;
        } else {
          failed++;
        }
      }
      return new SortingResult(true, relevant, changed, failed,
          failed == 0 ? "sorting reconciled" : "sorting failed for " + failed + " Jellyfin user(s)");
    } catch (Exception e) {
      return new SortingResult(false, 0, 0, 1, "Jellyfin is unreachable");
    }
  }

  private Optional<String> findFolderId(Configuration c, Path folder, Path managedRoot) throws Exception {
    Path normalizedFolder = folder.toAbsolutePath().normalize();
    Path normalizedRoot = managedRoot.toAbsolutePath().normalize();
    if (!normalizedFolder.startsWith(normalizedRoot)) return Optional.empty();
    String relative = normalizedRoot.relativize(normalizedFolder).toString().replace('\\', '/');

    // A user's FinTube root may itself be configured as a Jellyfin virtual folder.
    HttpResponse<String> virtual = request(c, "GET", "/Library/VirtualFolders", "");
    if (virtual.statusCode() / 100 == 2) {
      JsonNode folders = json.readTree(virtual.body());
      if (folders.isArray()) {
        for (JsonNode candidate : folders) {
          for (JsonNode location : candidate.path("Locations")) {
            if (sameManagedPath(location.asText(""), normalizedFolder, relative)) {
              String id = candidate.path("ItemId").asText("");
              if (!id.isBlank()) return Optional.of(id);
            }
          }
        }
      }
    }

    List<String> queries = List.of(
        "/Items?Recursive=true&Fields=Path&Limit=100&Path=" + enc(normalizedFolder.toString()),
        "/Items?Recursive=true&Fields=Path&Limit=100&SearchTerm=" + enc(normalizedFolder.getFileName().toString()));
    for (String query : queries) {
      HttpResponse<String> r = request(c, "GET", query, "");
      if (r.statusCode() / 100 != 2) continue;
      JsonNode items = json.readTree(r.body()).path("Items");
      if (!items.isArray()) continue;
      for (JsonNode item : items) {
        if (!item.path("IsFolder").asBoolean(false)) continue;
        if (!sameManagedPath(item.path("Path").asText(""), normalizedFolder, relative)) continue;
        String id = item.path("Id").asText("");
        if (!id.isBlank()) return Optional.of(id);
      }
    }
    return Optional.empty();
  }

  private JsonNode users(Configuration c) throws Exception {
    HttpResponse<String> r = request(c, "GET", "/Users", "");
    if (r.statusCode() / 100 != 2) return json.createArrayNode();
    return json.readTree(r.body());
  }

  private Optional<String> displayPreferencesId(Configuration c, String userId, String itemId)
      throws Exception {
    HttpResponse<String> r = request(c, "GET", "/Users/" + encPath(userId) + "/Items/"
        + encPath(itemId) + "?Fields=DisplayPreferencesId", "");
    if (r.statusCode() / 100 != 2) return Optional.empty();
    JsonNode item = json.readTree(r.body());
    if (!item.path("IsFolder").asBoolean(false)) return Optional.empty();
    String value = item.path("DisplayPreferencesId").asText("");
    return Optional.of(value.isBlank() ? itemId : value);
  }

  private Operation ensureSorting(Configuration c, String userId, String preferencesId,
                                  String sortBy, String sortOrder) throws Exception {
    String path = "/DisplayPreferences/" + encPath(preferencesId)
        + "?userId=" + enc(userId) + "&client=emby";
    HttpResponse<String> current = request(c, "GET", path, "");
    if (current.statusCode() / 100 != 2) {
      return new Operation(false, current.statusCode(), operationFailureMessage(current.statusCode()));
    }
    JsonNode parsed = json.readTree(current.body());
    if (!parsed.isObject()) return new Operation(false, 500, "invalid Jellyfin display preferences");
    ObjectNode body = (ObjectNode) parsed;
    boolean correct = sortBy.equalsIgnoreCase(body.path("SortBy").asText(""))
        && sortOrder.equalsIgnoreCase(body.path("SortOrder").asText(""))
        && body.path("RememberSorting").asBoolean(false);
    if (correct) return new Operation(true, 304, "sorting already correct");
    body.put("SortBy", sortBy);
    body.put("SortOrder", sortOrder);
    body.put("RememberSorting", true);
    HttpResponse<String> updated = request(c, "POST", path, json.writeValueAsString(body));
    if (updated.statusCode() / 100 == 2) {
      return new Operation(true, updated.statusCode(), "sorting updated");
    }
    return new Operation(false, updated.statusCode(), operationFailureMessage(updated.statusCode()));
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
      return new Operation(false, r.statusCode(), operationFailureMessage(r.statusCode()));
    } catch (Exception e) {
      return new Operation(false, 0, "Jellyfin is unreachable");
    }
  }

  private HttpResponse<String> request(Configuration c, String method, String path, String body)
      throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(c.baseUrl() + path))
        .timeout(c.requestTimeout())
        .header("Accept", "application/json");
    if (c.apiKeyConfigured()) b.header("Authorization", authorizationHeader(apiKey()));
    if ("GET".equals(method)) b.GET();
    else if ("POST".equals(method)) b.header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
    else if ("PUT".equals(method)) b.header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
    else throw new IllegalArgumentException("unsupported HTTP method");
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String apiKey() {
    return settings().getOrDefault("jellyfin_api_key", "");
  }

  static String authFailureMessage(int statusCode) {
    return statusCode == 401
        ? "Jellyfin rejected the API key; verify the key in Jellyfin and FinTube settings"
        : "Jellyfin denied the request; verify the API key permissions";
  }

  static String authorizationHeader(String apiKey) {
    return "MediaBrowser Token=\"" + apiKey + "\"";
  }

  static String operationFailureMessage(int statusCode) {
    if (statusCode == 401 || statusCode == 403) return authFailureMessage(statusCode);
    return "Jellyfin returned HTTP " + statusCode;
  }

  private Map<String, String> settings() {
    try { return settings.values(false); }
    catch (Exception e) { return Map.of(); }
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

  static boolean sameManagedPath(String candidate, Path expected, String relativeToManagedRoot) {
    if (candidate == null || candidate.isBlank() || expected == null) return false;
    try {
      Path actual = Path.of(candidate).toAbsolutePath().normalize();
      if (actual.equals(expected.toAbsolutePath().normalize())) return true;
      if (relativeToManagedRoot == null || relativeToManagedRoot.isBlank()) return false;
      String actualPath = actual.toString().replace('\\', '/');
      String suffix = relativeToManagedRoot.replace('\\', '/').replaceAll("^/+|/+$", "");
      return !suffix.isBlank() && actualPath.endsWith("/" + suffix);
    } catch (RuntimeException e) {
      return false;
    }
  }

  static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

  static String encPath(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
