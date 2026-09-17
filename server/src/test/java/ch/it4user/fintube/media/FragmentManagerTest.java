package ch.it4user.fintube.media;

import ch.it4user.fintube.core.Database;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FragmentManagerTest {
  @TempDir Path temp;
  Database db;
  HttpServer server;
  AtomicInteger requests;
  URI source;

  @BeforeEach
  void setUp() throws Exception {
    db = new Database();
    Field dataDir = Database.class.getDeclaredField("dataDir");
    dataDir.setAccessible(true);
    dataDir.set(db, temp.toString());
    db.init();
    requests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/fragment", exchange -> {
      requests.incrementAndGet();
      try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      byte[] bytes = "fragment-bytes".getBytes();
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
    FragmentManager manager = new FragmentManager(db, HttpClient.newHttpClient());
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
    FragmentManager manager = new FragmentManager(db, HttpClient.newHttpClient());
    manager.get("VIDEO1", "137", "v0", source, true);
    manager.get("VIDEO1", "137", "v500", source, true);
    assertEquals(2, requests.get());
    assertTrue(manager.isCached("VIDEO1", "137", "v500"));
  }

  @Test
  void activeReaderLeaseProtectsFragmentUntilClosed() throws Exception {
    FragmentManager manager = new FragmentManager(db, HttpClient.newHttpClient());
    var stream = manager.open("VIDEO1", "137", "v0", source, true);
    try (var c = db.open(); PreparedStatement p = c.prepareStatement("SELECT active_readers FROM cache_entries WHERE video_id='VIDEO1'")) {
      var r = p.executeQuery(); assertTrue(r.next()); assertEquals(1, r.getInt(1));
    }
    stream.close();
    try (var c = db.open(); PreparedStatement p = c.prepareStatement("SELECT active_readers FROM cache_entries WHERE video_id='VIDEO1'")) {
      var r = p.executeQuery(); assertTrue(r.next()); assertEquals(0, r.getInt(1));
    }
  }

  @Test
  void sourceSelectorPairsDashTracksUnderQualityCeiling() throws Exception {
    Database settings = mock(Database.class);
    when(settings.settings(false)).thenReturn(Map.of("stream_quality", "720", "preferred_video_codecs", "h264,vp9", "preferred_audio_codecs", "aac,opus"));
    MediaSourceService service = new MediaSourceService(settings);
    var root = new ObjectMapper().readTree("""
        {"duration":12,"formats":[
          {"format_id":"137","height":1080,"vcodec":"avc1.640028","acodec":"none","ext":"mp4","url":"http://video/1080"},
          {"format_id":"136","height":720,"vcodec":"avc1.4d401f","acodec":"none","ext":"mp4","url":"http://video/720","fragments":[{"url":"http://video/0","duration":6},{"url":"http://video/1","duration":6}]},
          {"format_id":"140","height":0,"vcodec":"none","acodec":"mp4a.40.2","ext":"m4a","url":"http://audio","fragments":[{"url":"http://audio/0","duration":6},{"url":"http://audio/1","duration":6}]}
        ]}"
        """);
    MediaSourceService.Source selected = service.select(root);
    assertEquals("136+140", selected.format());
    assertEquals("h264", selected.videoCodec());
    assertEquals("aac", selected.audioCodec());
    assertEquals(2, selected.fragments().size());
    assertNotNull(selected.fragments().get(1).audioUrl());
    assertFalse(selected.progressive());
  }

  @Test
  void persistedSourceSurvivesServiceRecreation() throws Exception {
    String json = """
        {"format":"137","videoFormat":"137","duration":12,"targetDuration":6,"videoCodec":"h264","audioCodec":"aac","container":"mp4","progressive":true,
         "fragments":[{"id":"v0","url":"http://persisted/0","seconds":6},{"id":"v1","url":"http://persisted/1","seconds":6}]}""";
    try (var c = db.open(); var p = c.prepareStatement("INSERT INTO media_sources(video_id,format_key,source_json,duration_seconds,updated_at) VALUES(?,?,?,?,?)")) {
      p.setString(1, "VIDEO1"); p.setString(2, "137"); p.setString(3, json); p.setInt(4, 12); p.setString(5, Database.now()); p.executeUpdate();
    }
    MediaSourceService.Source loaded = new MediaSourceService(db).source("VIDEO1");
    assertEquals("137", loaded.format());
    assertEquals(2, loaded.fragments().size());
    assertEquals(12, loaded.duration());
  }

  private Path get(FragmentManager manager, boolean high) {
    try { return manager.get("VIDEO1", "137", "v0", source, high); }
    catch (Exception e) { throw new RuntimeException(e); }
  }
}
