package ch.it4user.fintube.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JellyfinClientTest {
  private final ObjectMapper json = new ObjectMapper();

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
}
