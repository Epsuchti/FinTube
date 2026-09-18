package ch.it4user.fintube.web;

import ch.it4user.fintube.core.ApplicationClock;
import ch.it4user.fintube.core.ApplicationPaths;
import ch.it4user.fintube.persistence.entities.CacheEntryEntity;
import ch.it4user.fintube.persistence.repositories.CacheEntryRepository;
import ch.it4user.fintube.persistence.repositories.UserRepository;
import ch.it4user.fintube.persistence.entities.UserVideoEntity;
import ch.it4user.fintube.persistence.entities.UserVideoId;
import ch.it4user.fintube.persistence.repositories.UserVideoRepository;
import ch.it4user.fintube.persistence.entities.VideoEntity;
import ch.it4user.fintube.persistence.repositories.VideoRepository;
import ch.it4user.fintube.persistence.entities.YouTubeChannelEntity;
import ch.it4user.fintube.persistence.repositories.YouTubeChannelRepository;
import ch.it4user.fintube.persistence.entities.YouTubeSubscriptionEntity;
import ch.it4user.fintube.persistence.repositories.YouTubeSubscriptionRepository;
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
    @Autowired ApplicationPaths paths;
    @Autowired UserRepository users;
    @Autowired YouTubeChannelRepository channels;
    @Autowired YouTubeSubscriptionRepository subscriptions;
    @Autowired VideoRepository videos;
    @Autowired UserVideoRepository userVideos;
    @Autowired CacheEntryRepository cacheEntries;

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

        Path cached = paths.cacheRoot.resolve(videoId).resolve("fragment.bin");
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "shared-media");
        cacheEntries.save(new CacheEntryEntity(videoId, "137+140", "PARTIAL",
                ApplicationClock.now(), 0, 0, null));

        mvc.perform(delete("/api/subscriptions/" + subscriptionId).cookie(ericSession)).andExpect(status().isOk());

        assertThat(Files.exists(ericPath)).isFalse();
        assertThat(Files.exists(alicePath.resolve("video.strm"))).isTrue();
        assertThat(userVideos.findById(new UserVideoId(ericId, videoId))).isEmpty();
        assertThat(userVideos.findById(new UserVideoId(aliceId, videoId))).isPresent();
        assertThat(videos.findById(videoId)).isPresent();
        assertThat(cacheEntries.findById(videoId)).isPresent();
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
        return users.findByUsername(username).map(user -> user.getId())
                .orElseThrow(() -> new AssertionError("user was not created"));
    }

    private String slug(String username) throws Exception {
        return users.findByUsername(username).map(user -> user.getFilesystemSlug())
                .orElseThrow(() -> new AssertionError("user was not created"));
    }

    private Path libraryPath(String username, String videoId) throws Exception {
        return paths.usersRoot.resolve(slug(username)).resolve("Shared Channel").resolve(videoId);
    }

    private void seedChannel(String id) throws Exception {
        channels.save(new YouTubeChannelEntity(id, "Shared Channel",
                "https://www.youtube.com/channel/" + id, ApplicationClock.now()));
    }

    private long seedSubscription(long userId, String channelId) throws Exception {
        return subscriptions.save(new YouTubeSubscriptionEntity(userId, channelId, 1,
                ApplicationClock.now())).getId();
    }

    private void seedVideo(String videoId, String channelId) throws Exception {
        String now = ApplicationClock.now();
        videos.save(new VideoEntity(videoId, channelId, "Shared video", "", now,
                60, 0, "", "AVAILABLE", now));
    }

    private void seedUserVideo(long userId, String videoId, Path path) throws Exception {
        userVideos.save(new UserVideoEntity(userId, videoId, path.toString(),
                UUID.randomUUID().toString(), ApplicationClock.now()));
    }

    private static String unique(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    private static Path dataDirectory() {
        try { return Files.createTempDirectory("fintube-subscription-removal-"); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
}
