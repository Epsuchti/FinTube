package ch.it4user.fintube.media;

import ch.it4user.fintube.core.ApplicationPaths;
import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.persistence.repositories.CacheEntryRepository;
import ch.it4user.fintube.persistence.repositories.CachedFragmentRepository;
import ch.it4user.fintube.persistence.entities.MediaSourceEntity;
import ch.it4user.fintube.persistence.repositories.MediaSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.liquibase.enabled=true"
})
class FragmentManagerTest {
  private static final Path TEST_DATA_DIR = dataDirectory();

  @TempDir Path temp;
  @Autowired CacheEntryRepository cacheEntries;
  @Autowired CachedFragmentRepository cachedFragments;
  @Autowired MediaSourceRepository mediaSources;
  @Autowired ApplicationPaths paths;
  SettingsService settings;
  HttpServer server;
  AtomicInteger requests;
  URI source;

  @DynamicPropertySource
  static void dataProperties(DynamicPropertyRegistry registry) {
    registry.add("fintube.data-dir", TEST_DATA_DIR::toString);
  }

  @BeforeEach
  void setUp() throws Exception {
    cachedFragments.deleteAll();
    cacheEntries.deleteAll();
    mediaSources.deleteAll();
    paths.cacheRoot = temp.resolve("cache");
    Files.createDirectories(paths.cacheRoot);
    settings = mock(SettingsService.class);
    Map<String, String> settingValues = Map.of(
        "background_download_max_mbps", "75",
        "ffmpeg_path", "ffmpeg",
        "stream_quality", "720",
        "preferred_video_codecs", "h264,vp9",
        "preferred_audio_codecs", "aac,opus");
    when(settings.value(anyString())).thenAnswer(invocation -> settingValues.get(invocation.getArgument(0)));
    requests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/fragment", exchange -> {
      requests.incrementAndGet();
      try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      byte[] bytes = "fragment-bytes".getBytes();
      exchange.sendResponseHeaders(200, bytes.length);
      try (var out = exchange.getResponseBody()) { out.write(bytes); }
    });
    server.createContext("/flaky", exchange -> {
      int attempt = requests.incrementAndGet();
      if (attempt < 3) {
        exchange.sendResponseHeaders(503, -1);
        exchange.close();
        return;
      }
      byte[] bytes = "recovered-fragment".getBytes();
      exchange.sendResponseHeaders(200, bytes.length);
      try (var out = exchange.getResponseBody()) { out.write(bytes); }
    });
    server.createContext("/video.m3u8", exchange -> {
      byte[] bytes = """
          #EXTM3U
          #EXT-X-TARGETDURATION:6
          #EXTINF:6,
          video/0.ts
          #EXTINF:6,
          video/1.ts
          #EXT-X-ENDLIST
          """.getBytes();
      exchange.sendResponseHeaders(200, bytes.length);
      try (var out = exchange.getResponseBody()) { out.write(bytes); }
    });
    server.createContext("/audio.m3u8", exchange -> {
      byte[] bytes = """
          #EXTM3U
          #EXT-X-TARGETDURATION:6
          #EXTINF:6,
          audio/0.ts
          #EXTINF:6,
          audio/1.ts
          #EXT-X-ENDLIST
          """.getBytes();
      exchange.sendResponseHeaders(200, bytes.length);
      try (var out = exchange.getResponseBody()) { out.write(bytes); }
    });
    server.createContext("/video-fmp4.m3u8", exchange -> {
      byte[] bytes = """
          #EXTM3U
          #EXT-X-VERSION:6
          #EXT-X-MAP:URI="video/init.mp4"
          #EXTINF:6,
          video/0.m4s
          #EXTINF:6,
          video/1.m4s
          #EXT-X-ENDLIST
          """.getBytes();
      exchange.sendResponseHeaders(200, bytes.length);
      try (var out = exchange.getResponseBody()) { out.write(bytes); }
    });
    server.createContext("/adaptive/", exchange -> {
      String name = exchange.getRequestURI().getPath().substring("/adaptive/".length());
      byte[] all = switch (name) {
        case "vp9.webm" -> AdaptiveTestMedia.webm(new long[]{0, 5000, 10000}, 4000, 15000);
        case "h264.mp4", "av1.mp4" -> AdaptiveTestMedia.mp4(3, 3000, 1000, 5000);
        case "aac.m4a" -> AdaptiveTestMedia.mp4(2, 800, 44100, 330750);
        default -> null;
      };
      if (all == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
      String range = exchange.getRequestHeaders().getFirst("Range");
      int start = 0, end = all.length - 1;
      if (range != null) {
        String[] bounds = range.substring("bytes=".length()).split("-");
        start = Integer.parseInt(bounds[0]);
        end = Math.min(all.length - 1, Integer.parseInt(bounds[1]));
        exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + all.length);
      }
      exchange.sendResponseHeaders(range == null ? 200 : 206, end - start + 1);
      try (var out = exchange.getResponseBody()) { out.write(all, start, end - start + 1); }
    });
    server.start();
    source = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/fragment");
  }

  @AfterEach
  void tearDown() { if (server != null) server.stop(0); }

  @Test
  void concurrentPlaybackAndFillShareOneUpstreamRequest() throws Exception {
    FragmentManager manager = manager();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      var first = CompletableFuture.supplyAsync(() -> get(manager, false), pool);
      var second = CompletableFuture.supplyAsync(() -> get(manager, true), pool);
      Path a = first.get();
      Path b = second.get();
      assertEquals(a, b);
      assertEquals(1, requests.get(), "single-flight must issue one upstream request");
      assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
      manager.get("VIDEO1", "137", "v0", source, true);
      assertEquals(1, requests.get(), "a complete cache hit must not contact upstream");
    } finally { pool.shutdownNow(); }
  }

  @Test
  void seekFetchesOnlyRequestedUncachedFragment() throws Exception {
    FragmentManager manager = manager();
    manager.get("VIDEO1", "137", "v0", source, true);
    manager.get("VIDEO1", "137", "v500", source, true);
    assertEquals(2, requests.get());
    assertTrue(manager.isCached("VIDEO1", "137", "v500"));
  }

  @Test
  void transientUpstreamFailureBuffersAndRetriesTheSameFragment() throws Exception {
    FragmentManager manager = manager();
    URI flaky = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/flaky");

    Path result = manager.get("VIDEO1", "137", "v0", flaky, true);

    assertEquals("recovered-fragment", Files.readString(result));
    assertEquals(3, requests.get());
  }

  @Test
  void activeReaderLeaseProtectsFragmentUntilClosed() throws Exception {
    FragmentManager manager = manager();
    var stream = manager.open("VIDEO1", "137", "v0", source, true);
    assertEquals(1, cacheEntries.findById("VIDEO1").orElseThrow().getActiveReaders());
    stream.close();
    assertEquals(0, cacheEntries.findById("VIDEO1").orElseThrow().getActiveReaders());
  }

  @Test
  void sourceSelectorPairsDashTracksUnderQualityCeiling() throws Exception {
    MediaSourceService service = new MediaSourceService(settings, mediaSources);
    var root = new ObjectMapper().readTree("""
        {"duration":12,"formats":[
          {"format_id":"137","height":1080,"vcodec":"avc1.640028","acodec":"none","ext":"mp4","url":"http://video/1080"},
          {"format_id":"136","height":720,"vcodec":"avc1.4d401f","acodec":"none","ext":"mp4","url":"http://video/720","fragments":[{"url":"http://video/0","duration":6},{"url":"http://video/1","duration":6}]},
          {"format_id":"140","height":0,"vcodec":"none","acodec":"mp4a.40.2","ext":"m4a","url":"http://audio","fragments":[{"url":"http://audio/0","duration":6},{"url":"http://audio/1","duration":6}]}
        ]}"
        """);
    MediaSourceService.Source selected = service.select(root);
    assertEquals("136+140-tsv6", selected.format());
    assertEquals("h264", selected.videoCodec());
    assertEquals("aac", selected.audioCodec());
    assertEquals(2, selected.fragments().size());
    assertEquals(0d, selected.fragments().get(0).startSeconds());
    assertEquals(6d, selected.fragments().get(1).startSeconds());
    assertNotNull(selected.fragments().get(1).audioUrl());
    assertFalse(selected.progressive());
  }

  @Test
  void missingQualitySettingDefaultsTo1080p() throws Exception {
    when(settings.value("stream_quality")).thenReturn(null);
    MediaSourceService service = new MediaSourceService(settings, mediaSources);
    var root = new ObjectMapper().readTree("""
        {"duration":12,"formats":[
          {"format_id":"628","height":2160,"vcodec":"vp09.00.51.08","acodec":"none","ext":"mp4","url":"http://video/4k","fragments":[{"url":"http://video/4k-0","duration":6},{"url":"http://video/4k-1","duration":6}]},
          {"format_id":"312","height":1080,"vcodec":"avc1.64002a","acodec":"none","ext":"mp4","url":"http://video/1080","fragments":[{"url":"http://video/0","duration":6},{"url":"http://video/1","duration":6}]},
          {"format_id":"140","height":0,"vcodec":"none","acodec":"mp4a.40.2","ext":"m4a","url":"http://audio","fragments":[{"url":"http://audio/0","duration":6},{"url":"http://audio/1","duration":6}]}
        ]} """);
    assertEquals("312+140-tsv6", service.select(root).format());
  }

  @Test
  void higherResolutionPairedSourceBeatsLowerProgressiveSource() throws Exception {
    MediaSourceService service = new MediaSourceService(settings, mediaSources);
    when(settings.value("stream_quality")).thenReturn("1080");
    var root = new ObjectMapper().readTree("""
        {"duration":12,"formats":[
          {"format_id":"22","height":720,"vcodec":"avc1.64001f","acodec":"mp4a.40.2","ext":"mp4","url":"http://progressive","fragments":[{"url":"http://progressive/0","duration":6},{"url":"http://progressive/1","duration":6}]},
          {"format_id":"312","height":1080,"vcodec":"avc1.64002a","acodec":"none","ext":"mp4","url":"http://video","fragments":[{"url":"http://video/0","duration":6},{"url":"http://video/1","duration":6}]},
          {"format_id":"140","height":0,"vcodec":"none","acodec":"mp4a.40.2","ext":"m4a","url":"http://audio","fragments":[{"url":"http://audio/0","duration":6},{"url":"http://audio/1","duration":6}]}
        ]} """);
    assertEquals("312+140-tsv6", service.select(root).format());
  }

  @Test
  void sourceSelectorFallsBackToACompleteProgressiveFile() throws Exception {
    MediaSourceService service = new MediaSourceService(settings, mediaSources);
    when(settings.value("stream_quality")).thenReturn("1080");
    var root = new ObjectMapper().readTree("""
        {"duration":120,"formats":[
          {"format_id":"22","width":1280,"height":720,"vcodec":"avc1.64001f","acodec":"mp4a.40.2","ext":"mp4","url":"http://progressive"}
        ]} """);

    MediaSourceService.Source selected = service.select(root);

    assertEquals("22", selected.format());
    assertTrue(selected.progressive());
    assertEquals(1, selected.fragments().size());
    assertEquals("http://progressive", selected.fragments().getFirst().url().toString());
    assertEquals(120d, selected.fragments().getFirst().seconds());
  }

  @Test
  void sourceSelectorFallsBackToAnUnfragmentedAdaptivePair() throws Exception {
    MediaSourceService service = new MediaSourceService(settings, mediaSources);
    var root = new ObjectMapper().readTree("""
        {"duration":120,"formats":[
          {"format_id":"136","width":1280,"height":720,"vcodec":"avc1.4d401f","acodec":"none","ext":"mp4","url":"http://video"},
          {"format_id":"140","vcodec":"none","acodec":"mp4a.40.2","ext":"m4a","url":"http://audio"}
        ]} """);

    MediaSourceService.Source selected = service.select(root);

    assertEquals("136+140-tsv6", selected.format());
    assertFalse(selected.progressive());
    assertEquals(1, selected.fragments().size());
    assertEquals("http://video", selected.fragments().getFirst().url().toString());
    assertEquals("http://audio", selected.fragments().getFirst().audioUrl().toString());
  }

  @Test
  void sourceSelectorPrefersHigherQualityUnfragmentedPairOverProgressiveCompatibilityStream() throws Exception {
    MediaSourceService service = new MediaSourceService(settings, mediaSources);
    when(settings.value("stream_quality")).thenReturn("1080");
    var root = new ObjectMapper().readTree("""
        {"duration":120,"formats":[
          {"format_id":"18","width":640,"height":360,"vcodec":"avc1.42001E","acodec":"mp4a.40.2","ext":"mp4","url":"http://progressive/360"},
          {"format_id":"137","width":1920,"height":1080,"vcodec":"avc1.640028","acodec":"none","ext":"mp4","url":"http://video/1080"},
          {"format_id":"140","vcodec":"none","acodec":"mp4a.40.2","ext":"m4a","url":"http://audio"}
        ]} """);

    MediaSourceService.Source selected = service.select(root);

    assertEquals("137+140-tsv6", selected.format());
    assertFalse(selected.progressive());
    assertEquals(1080, selected.height());
  }

  @Test
  void sourceSelectorParsesHlsMediaPlaylists() throws Exception {
    MediaSourceService service = new MediaSourceService(settings, mediaSources);
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    var root = new ObjectMapper().readTree("""
        {"duration":12,"formats":[
          {"format_id":"311","height":720,"vcodec":"avc1.640020","acodec":"none","ext":"mp4","protocol":"m3u8_native","url":"%s/video.m3u8"},
          {"format_id":"234","height":0,"vcodec":"none","acodec":null,"ext":"mp4","protocol":"m3u8_native","url":"%s/audio.m3u8"}
        ]}
        """.formatted(base, base));

    MediaSourceService.Source selected = service.select(root);
    assertEquals("311+234-tsv6", selected.format());
    assertEquals(2, selected.fragments().size());
    assertEquals("http://127.0.0.1:" + server.getAddress().getPort() + "/audio/1.ts",
        selected.fragments().get(1).audioUrl().toString());
    assertFalse(selected.progressive());
  }

  @Test
  void sourceSelectorPrefersAacLcAndPreservesFmp4Initialization() throws Exception {
    MediaSourceService service = new MediaSourceService(settings, mediaSources);
    when(settings.value("stream_quality")).thenReturn("2160");
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    var root = new ObjectMapper().readTree("""
        {"duration":12,"formats":[
          {"format_id":"628","width":3840,"height":2160,"fps":60,"tbr":25000,"vcodec":"vp09.00.51.08","acodec":"none","ext":"mp4","protocol":"m3u8_native","url":"%s/video-fmp4.m3u8"},
          {"format_id":"233","height":0,"vcodec":"none","acodec":null,"tbr":0,"ext":"mp4","protocol":"m3u8_native","url":"%s/audio.m3u8"},
          {"format_id":"234","height":0,"vcodec":"none","acodec":null,"tbr":0,"ext":"mp4","protocol":"m3u8_native","url":"%s/audio.m3u8"}
        ]}
        """.formatted(base, base, base));

    MediaSourceService.Source selected = service.select(root);

    assertEquals("628+234-fmp4v1", selected.format());
    assertTrue(selected.fmp4());
    assertEquals(base + "/video/init.mp4", selected.videoInit().toString());
    assertEquals("vp09.00.51.08,mp4a.40.2", selected.codecs());
    assertEquals(3840, selected.width());
    assertEquals(2160, selected.height());
    assertEquals(60, selected.fps());
    assertTrue(selected.bandwidth() >= 25_000_000);
  }

  @Test
  void sourceSelectorRecognizesSuffixedHlsAudioAndPrefersOriginalTrack() throws Exception {
    MediaSourceService service = new MediaSourceService(settings, mediaSources);
    when(settings.value("stream_quality")).thenReturn("1080");
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    var root = new ObjectMapper().readTree("""
        {"duration":12,"formats":[
          {"format_id":"312","height":1080,"vcodec":"avc1.640020","acodec":"none","ext":"mp4","protocol":"m3u8_native","url":"%s/video.m3u8"},
          {"format_id":"233-0","height":0,"vcodec":"none","acodec":null,"ext":"mp4","protocol":"m3u8_native","language":"de","format_note":"Deutsch - dubbed-auto","url":"%s/audio.m3u8"},
          {"format_id":"233-20","height":0,"vcodec":"none","acodec":null,"ext":"mp4","protocol":"m3u8_native","language":"en-US","language_preference":10,"format_note":"American English - original (original)","url":"%s/audio.m3u8"}
        ]}
        """.formatted(base, base, base));

    MediaSourceService.Source selected = service.select(root);

    assertEquals("312+233-20-tsv6", selected.format());
    assertEquals("aac", selected.audioCodec());
    assertEquals(2, selected.fragments().size());
  }

  @Test
  void unavailableStreamTimestampFallsBackToUnshiftedRemux() {
    assertNull(FragmentManager.parseStreamStart("N/A"));
    assertNull(FragmentManager.parseStreamStart(""));
    assertEquals(1.25, FragmentManager.parseStreamStart("1.25"));
  }

  @Test
  void timestampLessAudioIsPlacedOnTheVideoTimeline() {
    assertEquals(22d, FragmentManager.audioOffset(22d, null, 22d));
    assertEquals(22d, FragmentManager.audioOffset(null, null, 22d));
    assertEquals(0.25d, FragmentManager.audioOffset(22d, 21.75d, 22d));
  }

  @Test
  void sourceSelectorRejectsAudioWithDifferentSegmentBoundaries() throws Exception {
    MediaSourceService service = new MediaSourceService(settings, mediaSources);
    var root = new ObjectMapper().readTree("""
        {"duration":12,"formats":[
          {"format_id":"136","height":720,"vcodec":"avc1.4d401f","acodec":"none","ext":"mp4","url":"http://video","fragments":[{"url":"http://video/0","duration":6},{"url":"http://video/1","duration":6}]},
          {"format_id":"140","height":0,"vcodec":"none","acodec":"mp4a.40.2","ext":"m4a","url":"http://audio-aac","fragments":[{"url":"http://audio-aac/0","duration":4},{"url":"http://audio-aac/1","duration":4},{"url":"http://audio-aac/2","duration":4}]},
          {"format_id":"251","height":0,"vcodec":"none","acodec":"opus","ext":"webm","url":"http://audio-opus","fragments":[{"url":"http://audio-opus/0","duration":6.02},{"url":"http://audio-opus/1","duration":5.98}]}
        ]}""");

    assertThrows(IllegalStateException.class, () -> service.select(root),
        "an unmatched AAC timeline must not fall back to Opus in MPEG-TS HLS");
  }

  @Test
  void persistedSourceSurvivesServiceRecreation() throws Exception {
    String json = """
        {"format":"137","videoFormat":"137","duration":12,"targetDuration":6,"videoCodec":"h264","audioCodec":"aac","container":"mp4","progressive":true,"bandwidth":8000000,"codecs":"avc1.640028,mp4a.40.2","selectionPolicy":"selector-v3|720|h264,vp9|aac,opus||",
         "fragments":[{"id":"v0","url":"http://persisted/0","seconds":6},{"id":"v1","url":"http://persisted/1","seconds":6}]}""";
    mediaSources.save(new MediaSourceEntity("VIDEO1", "137", json, 12, null, java.time.Instant.now().toString()));
    MediaSourceService.Source loaded = new MediaSourceService(settings, mediaSources).source("VIDEO1");
    assertEquals("137", loaded.format());
    assertEquals(2, loaded.fragments().size());
    assertEquals(12, loaded.duration());
  }

  @Test
  void rangeDownloadCachesOnlyTheRequestedBytes() throws Exception {
    FragmentManager manager = manager();
    URI file = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/adaptive/h264.mp4");
    byte[] all = AdaptiveTestMedia.mp4(3, 3000, 1000, 5000);

    Path part = manager.getRange("VIDEO1", "137-v0", "s1", file, 100, 400, true);

    assertArrayEquals(java.util.Arrays.copyOfRange(all, 100, 400), Files.readAllBytes(part));
  }

  @Test
  void personalCodecListIsAWhitelist() throws Exception {
    when(settings.value("stream_quality")).thenReturn("2160");
    MediaSourceService service = new MediaSourceService(settings, mediaSources);
    var root = new ObjectMapper().readTree("""
        {"duration":12,"formats":[
          {"format_id":"401","height":2160,"vcodec":"av01.0.12M.08","acodec":"none","ext":"mp4","url":"http://video/av1","fragments":[{"url":"http://video/a0","duration":6},{"url":"http://video/a1","duration":6}]},
          {"format_id":"312","height":1080,"vcodec":"avc1.64002a","acodec":"none","ext":"mp4","url":"http://video/1080","fragments":[{"url":"http://video/0","duration":6},{"url":"http://video/1","duration":6}]},
          {"format_id":"140","height":0,"vcodec":"none","acodec":"mp4a.40.2","ext":"m4a","url":"http://audio","fragments":[{"url":"http://audio/0","duration":6},{"url":"http://audio/1","duration":6}]}
        ]}""");

    assertEquals("312+140-tsv6", service.select(root).format(), "AV1 is not in h264,vp9 and must not be chosen");
  }

  @Test
  void httpsVp9AndAacAreServedAsIndexedTracksWithAnH264Variant() throws Exception {
    when(settings.value("stream_quality")).thenReturn("2160");
    when(settings.value("preferred_video_codecs")).thenReturn("vp9,h264");
    MediaSourceService service = new MediaSourceService(settings, mediaSources);
    String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/adaptive/";
    var root = new ObjectMapper().readTree("""
        {"duration":15,"formats":[
          {"format_id":"18","width":640,"height":360,"vcodec":"avc1.42001E","acodec":"mp4a.40.2","ext":"mp4","protocol":"https","url":"%1$sh264.mp4"},
          {"format_id":"137","width":1920,"height":1080,"fps":30,"tbr":4000,"vcodec":"avc1.640028","acodec":"none","ext":"mp4","protocol":"https","url":"%1$sh264.mp4"},
          {"format_id":"313","width":3840,"height":2160,"fps":30,"tbr":18000,"vcodec":"vp9","acodec":"none","ext":"webm","protocol":"https","url":"%1$svp9.webm"},
          {"format_id":"401","width":3840,"height":2160,"fps":30,"tbr":15000,"vcodec":"av01.0.12M.08","acodec":"none","ext":"mp4","protocol":"https","url":"%1$sav1.mp4"},
          {"format_id":"251","vcodec":"none","acodec":"opus","abr":140,"ext":"webm","protocol":"https","url":"%1$svp9.webm"},
          {"format_id":"140","vcodec":"none","acodec":"mp4a.40.2","abr":129,"ext":"m4a","protocol":"https","url":"%1$saac.m4a"}
        ]}""".formatted(base));

    MediaSourceService.Source selected = service.select(root);

    assertEquals("313+140~137-idxv1", selected.format());
    assertTrue(selected.indexed());
    assertEquals(List.of("v0", "v1", "a"), selected.tracks().stream().map(MediaSourceService.IndexedTrack::id).toList());
    assertEquals("137", selected.track("v0").formatId(), "the H.264 compatibility variant is listed first");
    assertEquals("313", selected.track("v1").formatId());
    assertEquals("vp09.00.50.08", selected.track("v1").codecs());
    assertEquals("webm", selected.track("v1").container());
    assertEquals(3, selected.track("v1").segments().size());
    assertEquals("mp4a.40.2", selected.audioTrack().codecs());
    assertEquals(2160, selected.height());
  }

  @Test
  void unindexableVp9IsNeverRemuxedIntoMpegTs() throws Exception {
    when(settings.value("preferred_video_codecs")).thenReturn("vp9");
    MediaSourceService service = new MediaSourceService(settings, mediaSources);
    String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/adaptive/";
    var root = new ObjectMapper().readTree("""
        {"duration":15,"formats":[
          {"format_id":"247","width":1280,"height":720,"vcodec":"vp9","acodec":"none","ext":"webm","protocol":"https","url":"%1$smissing.webm"},
          {"format_id":"140","vcodec":"none","acodec":"mp4a.40.2","ext":"m4a","protocol":"https","url":"%1$saac.m4a"}
        ]}""".formatted(base));

    assertThrows(IllegalStateException.class, () -> service.select(root));
  }

  private Path get(FragmentManager manager, boolean high) {
    try { return manager.get("VIDEO1", "137", "v0", source, high); }
    catch (Exception e) { throw new RuntimeException(e); }
  }

  private FragmentManager manager() {
    return new FragmentManager(paths, settings, cacheEntries, cachedFragments, HttpClient.newHttpClient());
  }

  private static Path dataDirectory() {
    try { return Files.createTempDirectory("fintube-media-test-"); }
    catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }
}
