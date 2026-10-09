package ch.it4user.fintube.media;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaterializedVideoDownloadServiceTest {
  @Test
  void downloadCommandUsesTheSameYouTubeAccessSettingsAsSourceProbes() {
    List<String> command = MaterializedVideoDownloadService.downloadCommand(Map.of(
        "yt_dlp_path", "/usr/bin/yt-dlp",
        "cookie_file", "/secrets/youtube.cookies",
        "youtube_player_client", "web",
        "youtube_po_token", "token",
        "youtube_po_token_provider_enabled", "true",
        "youtube_po_token_provider_args", "youtubepot-bgutilhttp:base_url=http://pot-provider:4416"),
        "http://proxy:8080", "", Path.of("/media/video.%(ext)s"), "video-id");

    assertThat(command).startsWith("/usr/bin/yt-dlp")
        .containsSubsequence("--proxy", "http://proxy:8080")
        .containsSubsequence("--cookies", "/secrets/youtube.cookies")
        .containsSubsequence("--extractor-args", "youtube:player_client=web")
        .containsSubsequence("--extractor-args", "youtube:po_token=token")
        .containsSubsequence("--extractor-args", "youtubepot-bgutilhttp:base_url=http://pot-provider:4416")
        .endsWith("-o", "/media/video.%(ext)s", "https://www.youtube.com/watch?v=video-id");
  }

  @Test
  void configuredProviderUrlOverridesTheStoredProviderArgument() {
    List<String> command = MaterializedVideoDownloadService.downloadCommand(Map.of(
        "youtube_po_token_provider_enabled", "true",
        "youtube_po_token_provider_args", "stored-provider"),
        "", "http://configured-provider:4416", Path.of("video.%(ext)s"), "video");

    assertThat(command).containsSubsequence("--extractor-args",
        "youtubepot-bgutilhttp:base_url=http://configured-provider:4416");
  }
}
