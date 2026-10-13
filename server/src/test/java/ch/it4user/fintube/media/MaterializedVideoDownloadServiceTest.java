package ch.it4user.fintube.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
  void downloadPrefersH264AndAacWhenResolutionAndFrameRateAreEqual() {
    List<String> command = MaterializedVideoDownloadService.downloadCommand(Map.of(), "", "",
        Path.of("video.%(ext)s"), "video");

    assertThat(command).containsSubsequence("-f", "bv*+ba/b")
        .containsSubsequence("-S", "res,fps,hdr:SDR,+codec:avc:m4a");
  }

  @Test
  void parsesFfprobeStreamsAndDetectsIncompatibleCodecs() {
    var info = MaterializedVideoDownloadService.parseStreams(
        "codec_name=av1|codec_type=video|pix_fmt=yuv420p\ncodec_name=opus|codec_type=audio\n");

    assertThat(info.videoCodec()).isEqualTo("av1");
    assertThat(info.audioCodec()).isEqualTo("opus");
    assertThat(info.directPlayable()).isFalse();
    assertThat(MaterializedVideoDownloadService.parseStreams(
        "codec_name=h264|codec_type=video|pix_fmt=yuv420p\ncodec_name=aac|codec_type=audio").directPlayable()).isTrue();
    assertThat(MaterializedVideoDownloadService.parseStreams(
        "codec_name=h264|codec_type=video|pix_fmt=yuv420p10le\ncodec_name=aac|codec_type=audio").directPlayable()).isFalse();
    assertThat(MaterializedVideoDownloadService.parseStreams("codec_name=aac|codec_type=audio")).isNull();
  }

  @Test
  void compatibilityCommandReencodesOnlyIncompatibleStreams() {
    var av1Opus = new MaterializedVideoDownloadService.StreamInfo("av1", "yuv420p", "opus");
    assertThat(MaterializedVideoDownloadService.compatibilityCommand("ffmpeg", Path.of("in.mp4"), Path.of("out.mp4"), av1Opus))
        .containsSubsequence("-c:v", "libx264")
        .containsSubsequence("-pix_fmt", "yuv420p")
        .containsSubsequence("-c:a", "aac")
        .endsWith("-movflags", "+faststart", "out.mp4");

    var h264Opus = new MaterializedVideoDownloadService.StreamInfo("h264", "yuv420p", "opus");
    assertThat(MaterializedVideoDownloadService.compatibilityCommand("ffmpeg", Path.of("in.mp4"), Path.of("out.mp4"), h264Opus))
        .containsSubsequence("-c:v", "copy")
        .containsSubsequence("-c:a", "aac");
  }

  @Test
  void evictRemovesTheDownloadButKeepsFavoritesAndMetadata(@TempDir Path folder) throws Exception {
    Files.writeString(folder.resolve("video.mp4"), "media");
    Files.writeString(folder.resolve("video.nfo"), "<movie/>");

    MaterializedVideoDownloadService.markFavorite(folder, true);
    assertThat(MaterializedVideoDownloadService.evict(folder)).isFalse();
    assertThat(folder.resolve("video.mp4")).exists();

    MaterializedVideoDownloadService.markFavorite(folder, false);
    assertThat(MaterializedVideoDownloadService.evict(folder)).isTrue();
    assertThat(folder.resolve("video.mp4")).doesNotExist();
    assertThat(folder.resolve("video.nfo")).exists();
    assertThat(MaterializedVideoDownloadService.evict(folder)).isFalse();
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
