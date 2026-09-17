package ch.it4user.fintube.web;

import ch.it4user.fintube.core.Database;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that removing a user's subscription cleans only that user's
 * generated Jellyfin entries.  The canonical video and shared cache remain
 * available to another subscriber.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubscriptionRemovalTest {
    private static final Path TEST_DATA_DIR = dataDirectory();

    @Autowired MockMvc mvc;
    @Autowired Database database;

    @DynamicPropertySource
    static void dataProperties(DynamicPropertyRegistry registry) {
        registry.add("fintube.data-dir", TEST_DATA_DIR::toString);
    }

    @Test
    void removesOnlyOwnerLibraryAndKeepsSharedVideoAndCache() throws Exception {
        String eric = unique("eric");
        String alice = unique("alice");
        Cookie ericSession = register(eric, "eric password that is long");
        register(alice, "alice password that is long");
        long ericId = userId(eric);
        long aliceId = userId(alice);
        String channelId = "UC" + UUID.randomUUID().toString().replace("-", "");
        String videoId = "video" + UUID.randomUUID().toString().replace("-", "");
        seedChannel(channelId);
        long subscriptionId = seedSubscription(ericId, channelId);
        seedSubscription(aliceId, channelId);
        seedVideo(videoId, channelId);

        Path ericPath = libraryPath(eric, videoId);
        Path alicePath = libraryPath(alice, videoId);
        Files.createDirectories(ericPath);
        Files.createDirectories(alicePath);
        Files.writeString(ericPath.resolve("video.strm"), "http://bridge/play/" + videoId);
        Files.writeString(alicePath.resolve("video.strm"), "http://bridge/play/" + videoId);
        seedUserVideo(ericId, videoId, ericPath);
        seedUserVideo(aliceId, videoId, alicePath);

        Path cached = database.cacheRoot.resolve(videoId).resolve("fragment.bin");
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "shared-media");
        try (var c = database.open(); var p = c.prepareStatement("INSERT OR REPLACE INTO cache_entries(video_id,format_key,status,last_accessed_at,active_readers,active_writers) VALUES(?,?,?,?,0,0)")) {
            p.setString(1, videoId); p.setString(2, "137+140"); p.setString(3, "PARTIAL"); p.setString(4, Database.now()); p.executeUpdate();
        }

        mvc.perform(delete("/api/subscriptions/" + subscriptionId).cookie(ericSession)).andExpect(status().isOk());

        assertThat(Files.exists(ericPath)).isFalse();
        assertThat(Files.exists(alicePath.resolve("video.strm"))).isTrue();
        assertThat(count("SELECT count(*) FROM user_videos WHERE user_id=? AND video_id=?", ericId, videoId)).isZero();
        assertThat(count("SELECT count(*) FROM user_videos WHERE user_id=? AND video_id=?", aliceId, videoId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM videos WHERE video_id=?", videoId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM cache_entries WHERE video_id=?", videoId)).isEqualTo(1);
        assertThat(Files.exists(cached)).isTrue();
    }

    private Cookie register(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("HttpOnly");
        String value = setCookie.split(";", 2)[0];
        return new Cookie("FT_SESSION", value.substring("FT_SESSION=".length()));
    }

    private long userId(String username) throws Exception {
        try (var c = database.open(); var p = c.prepareStatement("SELECT id FROM users WHERE username=?")) {
            p.setString(1, username);
            try (ResultSet r = p.executeQuery()) { assertThat(r.next()).isTrue(); return r.getLong(1); }
        }
    }

    private String slug(String username) throws Exception {
        try (var c = database.open(); var p = c.prepareStatement("SELECT filesystem_slug FROM users WHERE username=?")) {
            p.setString(1, username);
            try (ResultSet r = p.executeQuery()) { assertThat(r.next()).isTrue(); return r.getString(1); }
        }
    }

    private Path libraryPath(String username, String videoId) throws Exception {
        return database.usersRoot.resolve(slug(username)).resolve("Shared Channel [UCshared]").resolve(videoId);
    }

    private void seedChannel(String id) throws Exception {
        try (var c = database.open(); var p = c.prepareStatement("INSERT OR IGNORE INTO youtube_channels(channel_id,name,url,updated_at) VALUES(?,?,?,?)")) {
            p.setString(1, id); p.setString(2, "Shared Channel"); p.setString(3, "https://www.youtube.com/channel/" + id); p.setString(4, Database.now()); p.executeUpdate();
        }
    }

    private long seedSubscription(long userId, String channelId) throws Exception {
        try (var c = database.open(); var p = c.prepareStatement("INSERT INTO youtube_subscriptions(user_id,channel_id,created_at) VALUES(?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, userId); p.setString(2, channelId); p.setString(3, Database.now()); p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) { assertThat(r.next()).isTrue(); return r.getLong(1); }
        }
    }

    private void seedVideo(String videoId, String channelId) throws Exception {
        try (var c = database.open(); var p = c.prepareStatement("INSERT INTO videos(video_id,channel_id,title,description,published_at,duration_seconds,is_short,thumbnail_url,availability,metadata_updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, videoId); p.setString(2, channelId); p.setString(3, "Shared video"); p.setString(4, ""); p.setString(5, Database.now()); p.setInt(6, 60); p.setInt(7, 0); p.setString(8, ""); p.setString(9, "AVAILABLE"); p.setString(10, Database.now()); p.executeUpdate();
        }
    }

    private void seedUserVideo(long userId, String videoId, Path path) throws Exception {
        try (var c = database.open(); var p = c.prepareStatement("INSERT INTO user_videos(user_id,video_id,library_path,playback_token,created_at) VALUES(?,?,?,?,?)")) {
            p.setLong(1, userId); p.setString(2, videoId); p.setString(3, path.toString()); p.setString(4, UUID.randomUUID().toString()); p.setString(5, Database.now()); p.executeUpdate();
        }
    }

    private long count(String sql, Object... args) throws Exception {
        try (var c = database.open(); var p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
            try (ResultSet r = p.executeQuery()) { assertThat(r.next()).isTrue(); return r.getLong(1); }
        }
    }

    private static String unique(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    private static Path dataDirectory() {
        try { return Files.createTempDirectory("fintube-subscription-removal-"); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
}
