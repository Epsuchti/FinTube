package ch.it4user.fintube.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ch.it4user.fintube.api.contract.model.MediaPreferences;
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
  void downloadDefaultsTo1080pWithTheDefaultCodecListAndAacAudio() {
    List<String> command = MaterializedVideoDownloadService.downloadCommand(Map.of(), "", "",
        Path.of("video.%(ext)s"), "video");

    String video = "bv*[height<=1080][vcodec~='^(avc1|h264|vp0?9|av01)']";
    assertThat(command).containsSubsequence("-f", video + "+ba[acodec^=mp4a]/" + video + "+ba/b[height<=1080]/bv*+ba/b")
        .containsSubsequence("-S", "res,fps,hdr:SDR,vcodec:h264,acodec:aac");
  }

  @Test
  void downloadHonoursPersonalQualityAndCodecWhitelist() {
    var preferences = new MediaPreferences(MediaPreferences.QualityEnum.fromValue("2160"), "vp9,h264", "aac");
    List<String> command = MaterializedVideoDownloadService.downloadCommand(Map.of(), "", "",
        Path.of("video.%(ext)s"), "video", preferences);

    String video = "bv*[height<=2160][vcodec~='^(vp0?9|avc1|h264)']";
    assertThat(command).containsSubsequence("-f", video + "+ba[acodec^=mp4a]/" + video + "+ba/b[height<=2160]/bv*+ba/b")
        .containsSubsequence("-S", "res,fps,hdr:SDR,vcodec:vp9,acodec:aac")
        .containsSubsequence("--merge-output-format", "mp4");
  }

  @Test
  void acceptedVp9IsCopiedAndOnlyOpusIsConverted() {
    Set<String> vp9 = Set.of("vp9", "h264");
    var vp9Opus = new MaterializedVideoDownloadService.StreamInfo("vp9", "yuv420p", "opus");
    assertThat(vp9Opus.directPlayable(vp9)).isFalse();
    assertThat(MaterializedVideoDownloadService.compatibilityCommand("ffmpeg", Path.of("in.webm"), Path.of("out.mp4"), vp9Opus, vp9))
        .containsSubsequence("-c:v", "copy")
        .containsSubsequence("-c:a", "aac");
    assertThat(new MaterializedVideoDownloadService.StreamInfo("vp9", "yuv420p", "aac").directPlayable(vp9)).isTrue();
    assertThat(new MaterializedVideoDownloadService.StreamInfo("vp9", "yuv420p10le", "aac").directPlayable(vp9)).isFalse();
    assertThat(MaterializedVideoDownloadService.compatibilityCommand("ffmpeg", Path.of("in.webm"), Path.of("out.mp4"), vp9Opus, Set.of("h264")))
        .containsSubsequence("-c:v", "libx264");
    assertThat(MaterializedVideoDownloadService.allowedVideoCodecs(
        new MediaPreferences(MediaPreferences.QualityEnum.fromValue("1080"), "vp9,av1", "aac"))).containsExactly("vp9", "av1");
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
