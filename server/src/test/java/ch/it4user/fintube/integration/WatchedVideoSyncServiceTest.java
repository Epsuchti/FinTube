package ch.it4user.fintube.integration;

import ch.it4user.fintube.persistence.entities.UserVideoEntity;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WatchedVideoSyncServiceTest {
  @Test
  void matchesLibraryPathAcrossDifferentContainerMountPoints() {
    Path userRoot = Path.of("/fintube/users/eric");
    Path library = userRoot.resolve("Channel Name/VIDEO123");

    assertThat(WatchedVideoSyncService.pathMatches(
        "/media/eric/Channel Name/VIDEO123/video.strm", library, userRoot)).isTrue();
    assertThat(WatchedVideoSyncService.pathMatches(
        "/media/alice/Channel Name/VIDEO123/video.strm", library, userRoot)).isFalse();
  }

  @Test
  void providerIdFallbackOnlyMatchesAnUnambiguousUserLink() {
    UserVideoEntity eric = new UserVideoEntity(1L, "VIDEO123",
        "/fintube/users/eric/Channel/VIDEO123", "token-1", "now");
    UserVideoEntity alice = new UserVideoEntity(2L, "VIDEO123",
        "/fintube/users/alice/Channel/VIDEO123", "token-2", "now");
    JellyfinClient.PlayedItem played = new JellyfinClient.PlayedItem("item", "", "VIDEO123");

    assertThat(WatchedVideoSyncService.matchingLink(played, List.of(eric),
        Map.of(1L, Path.of("/fintube/users/eric")))).isSameAs(eric);
    assertThat(WatchedVideoSyncService.matchingLink(played, List.of(eric, alice), Map.of(
        1L, Path.of("/fintube/users/eric"), 2L, Path.of("/fintube/users/alice")))).isNull();
  }

  @Test
  void youtubeMarkerUsesCookiesWithoutInvokingAShell() {
    List<String> command = WatchedVideoSyncService.youtubeMarkCommand(List.of("-video-id", "second"),
        Map.of("yt_dlp_path", "/usr/bin/yt-dlp", "youtube_player_client", "web"),
        "/secrets/youtube.cookies", "http://proxy:8080");

    assertThat(command).containsSubsequence("--simulate", "--mark-watched")
        .containsSubsequence("--cookies", "/secrets/youtube.cookies")
        .containsSubsequence("--proxy", "http://proxy:8080")
        .containsSubsequence("--extractor-args", "youtube:player_client=web")
        .endsWith("--", "https://www.youtube.com/watch?v=-video-id",
            "https://www.youtube.com/watch?v=second");
  }
}
