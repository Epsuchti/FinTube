package ch.it4user.fintube.integration;

import ch.it4user.fintube.core.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JellyfinClientTest {
  private final ObjectMapper json = new ObjectMapper();
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void normalizesOnlyHttpUrlsAndRemovesTrailingSlash() {
    assertThat(JellyfinClient.normalizeBaseUrl("http://jellyfin:8096///"))
        .isEqualTo("http://jellyfin:8096");
    assertThat(JellyfinClient.normalizeBaseUrl("https://jf.example/jellyfin"))
        .isEqualTo("https://jf.example/jellyfin");
    assertThat(JellyfinClient.normalizeBaseUrl("file:///tmp/jellyfin")).isEmpty();
    assertThat(JellyfinClient.normalizeBaseUrl("http://user:pass@jellyfin:8096")).isEmpty();
  }

  @Test
  void providerAndPathMatchingAreStrictEnoughForRuntimeSync() throws Exception {
    var providers = json.readTree("{\"YouTube\":\"VIDEO123\"}");
    assertThat(JellyfinClient.providerId(providers)).isEqualTo("VIDEO123");
    assertThat(JellyfinClient.sameOrChild("/data/users/eric/Channel/VIDEO123/video.strm",
        Path.of("/data/users/eric"))).isTrue();
    assertThat(JellyfinClient.sameOrChild("/data/users/alice/VIDEO123/video.strm",
        Path.of("/data/users/eric"))).isFalse();
  }

  @Test
  void parsesBooleanAliasesAndFallsBackForInvalidValues() {
    assertThat(JellyfinClient.parseBoolean("yes", false)).isTrue();
    assertThat(JellyfinClient.parseBoolean("0", true)).isFalse();
    assertThat(JellyfinClient.parseBoolean("maybe", true)).isTrue();
  }

  @Test
  void explainsAuthenticationFailures() {
    assertThat(JellyfinClient.operationFailureMessage(401))
        .contains("rejected the API key");
    assertThat(JellyfinClient.operationFailureMessage(403))
        .contains("denied the request");
    assertThat(JellyfinClient.operationFailureMessage(500))
        .isEqualTo("Jellyfin returned HTTP 500");
  }

  @Test
  void usesJellyfinModernAuthorizationHeader() {
    assertThat(JellyfinClient.authorizationHeader("api-key"))
        .isEqualTo("MediaBrowser Token=\"api-key\"");
  }

  @Test
  void readModifyWritesSortingOnlyForUsersWhoCanSeeTheManagedFolder() throws Exception {
    AtomicInteger posts = new AtomicInteger();
    AtomicReference<String> preferences = new AtomicReference<>("""
        {"Id":"prefs-1","SortBy":"SortName","SortOrder":"Ascending",\
        "RememberSorting":false,"ShowBackdrop":true,"CustomPrefs":{"unrelated":"keep-me"}}""");
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/", exchange -> respond(exchange, route(exchange, preferences, posts)));
    server.start();

    SettingsService settings = mock(SettingsService.class);
    when(settings.values(false)).thenReturn(Map.of(
        "jellyfin_enabled", "true",
        "jellyfin_url", "http://localhost:" + server.getAddress().getPort(),
        "jellyfin_api_key", "secret"));
    JellyfinClient client = new JellyfinClient(settings, json,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());

    Path managedRoot = Path.of("/host/fintube/users");
    Path folder = managedRoot.resolve("eric/Channel Name");
    JellyfinClient.SortingResult first = client.ensureFolderSorting(
        folder, managedRoot, "PremiereDate", "Descending");
    JellyfinClient.SortingResult second = client.ensureFolderSorting(
        folder, managedRoot, "PremiereDate", "Descending");

    assertThat(first.itemFound()).isTrue();
    assertThat(first.users()).isEqualTo(1);
    assertThat(first.changed()).isEqualTo(1);
    assertThat(second.changed()).isZero();
    assertThat(posts).hasValue(1);
    assertThat(json.readTree(preferences.get()).path("SortBy").asText()).isEqualTo("PremiereDate");
    assertThat(json.readTree(preferences.get()).path("SortOrder").asText()).isEqualTo("Descending");
    assertThat(json.readTree(preferences.get()).path("RememberSorting").asBoolean()).isTrue();
    assertThat(json.readTree(preferences.get()).path("ShowBackdrop").asBoolean()).isTrue();
    assertThat(json.readTree(preferences.get()).path("CustomPrefs").path("unrelated").asText())
        .isEqualTo("keep-me");
  }

  private String route(HttpExchange exchange, AtomicReference<String> preferences,
                       AtomicInteger posts) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (path.equals("/Library/VirtualFolders")) return "[]";
    if (path.equals("/Items")) return """
        {"Items":[{"Id":"folder-1","IsFolder":true,\
        "Path":"/jellyfin/users/eric/Channel Name"}]}""";
    if (path.equals("/Users")) return "[{\"Id\":\"user-1\"},{\"Id\":\"user-2\"}]";
    if (path.equals("/Users/user-1/Items/folder-1")) {
      return "{\"Id\":\"folder-1\",\"IsFolder\":true,\"DisplayPreferencesId\":\"prefs-1\"}";
    }
    if (path.equals("/DisplayPreferences/prefs-1")) {
      if (exchange.getRequestMethod().equals("POST")) {
        posts.incrementAndGet();
        preferences.set(new String(exchange.getRequestBody().readAllBytes()));
        return "";
      }
      return preferences.get();
    }
    return null;
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    if (body == null) {
      exchange.sendResponseHeaders(404, -1);
    } else {
      byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(exchange.getRequestMethod().equals("POST") ? 204 : 200,
          exchange.getRequestMethod().equals("POST") ? -1 : bytes.length);
      if (!exchange.getRequestMethod().equals("POST")) exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }
}
