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
    assertEquals("136+140-tsv2", selected.format());
    assertEquals("h264", selected.videoCodec());
    assertEquals("aac", selected.audioCodec());
    assertEquals(2, selected.fragments().size());
    assertNotNull(selected.fragments().get(1).audioUrl());
    assertFalse(selected.progressive());
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
    assertEquals("311+234-tsv2", selected.format());
    assertEquals(2, selected.fragments().size());
    assertEquals("http://127.0.0.1:" + server.getAddress().getPort() + "/audio/1.ts",
        selected.fragments().get(1).audioUrl().toString());
    assertFalse(selected.progressive());
  }

  @Test
  void persistedSourceSurvivesServiceRecreation() throws Exception {
    String json = """
        {"format":"137","videoFormat":"137","duration":12,"targetDuration":6,"videoCodec":"h264","audioCodec":"aac","container":"mp4","progressive":true,
         "fragments":[{"id":"v0","url":"http://persisted/0","seconds":6},{"id":"v1","url":"http://persisted/1","seconds":6}]}""";
    mediaSources.save(new MediaSourceEntity("VIDEO1", "137", json, 12, null, java.time.Instant.now().toString()));
    MediaSourceService.Source loaded = new MediaSourceService(settings, mediaSources).source("VIDEO1");
    assertEquals("137", loaded.format());
    assertEquals(2, loaded.fragments().size());
    assertEquals(12, loaded.duration());
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
