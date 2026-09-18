package ch.it4user.fintube.web;

import ch.it4user.fintube.core.ApplicationClock;
import ch.it4user.fintube.core.ApplicationPaths;
import ch.it4user.fintube.core.SettingsPolicy;
import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.persistence.entities.UserEntity;
import ch.it4user.fintube.persistence.repositories.UserRepository;
import ch.it4user.fintube.persistence.entities.YouTubeChannelEntity;
import ch.it4user.fintube.persistence.repositories.YouTubeChannelRepository;
import ch.it4user.fintube.persistence.entities.YouTubeSubscriptionEntity;
import ch.it4user.fintube.persistence.repositories.YouTubeSubscriptionRepository;
import ch.it4user.fintube.persistence.entities.UserVideoEntity;
import ch.it4user.fintube.persistence.entities.VideoEntity;
import ch.it4user.fintube.persistence.repositories.UserVideoRepository;
import ch.it4user.fintube.persistence.repositories.VideoRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ownership-focused integration checks. These intentionally exercise the same
 * HTTP boundary used by the UI, while using the database only to seed canonical
 * shared metadata that would normally come from YouTube.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OwnershipIsolationTest {
    private static final Path TEST_DATA_DIR = dataDirectory();

    @Autowired MockMvc mvc;
    @Autowired ApplicationPaths paths;
    @Autowired UserRepository users;
    @Autowired YouTubeChannelRepository channels;
    @Autowired YouTubeSubscriptionRepository subscriptions;
    @Autowired UserVideoRepository userVideos;
    @Autowired VideoRepository videos;
    @Autowired SettingsService settings;

    @DynamicPropertySource
    static void dataProperties(DynamicPropertyRegistry registry) {
        registry.add("fintube.data-dir", TEST_DATA_DIR::toString);
    }

    @Test
    void registrationsHashPasswordsAndCreatePrivateFilesystemRoots() throws Exception {
        String username = unique("path");
        Cookie session = register(username, "correct horse battery staple");

        assertThat(session.getValue()).isNotBlank();
        UserEntity user = users.findByUsername(username).orElseThrow();
        assertThat(user.getPasswordHash()).doesNotContain("correct horse battery staple");
        String slug = user.getFilesystemSlug();
        assertThat(slug).matches("[a-z0-9]+(?:-[a-z0-9]+)*");
        assertThat(Files.isDirectory(paths.usersRoot.resolve(slug))).isTrue();
        assertThat(paths.usersRoot.resolve(slug).normalize().startsWith(paths.usersRoot.normalize())).isTrue();

        mvc.perform(get("/api/subscriptions").cookie(session)).andExpect(status().isOk());
        mvc.perform(get("/api/subscriptions")).andExpect(status().isUnauthorized());
    }

    @Test
    void subscriptionsAreOwnedByTheAuthenticatedUser() throws Exception {
        String alice = unique("alice");
        String eric = unique("eric");
        Cookie aliceSession = register(alice, "alice password that is long");
        Cookie ericSession = register(eric, "eric password that is long");
        long aliceId = userId(alice);
        long ericId = userId(eric);
        String channelId = "UC" + UUID.randomUUID().toString().replace("-", "");
        seedChannel(channelId, "Shared Channel");
        seedSubscription(aliceId, channelId);
        seedSubscription(ericId, channelId);
        long aliceSubscription = subscriptionId(aliceId);
        long ericSubscription = subscriptionId(ericId);

        mvc.perform(get("/api/subscriptions").cookie(aliceSession)).andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("Shared Channel"));
        mvc.perform(get("/api/subscriptions").cookie(ericSession)).andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("Shared Channel"));

        mvc.perform(patch("/api/subscriptions/" + ericSubscription).cookie(aliceSession)
                        .contentType(APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/subscriptions/" + ericSubscription).cookie(aliceSession))
                .andExpect(status().isNotFound());
        assertThat(subscriptionOwner(ericSubscription)).isEqualTo(ericId);
        assertThat(subscriptionOwner(aliceSubscription)).isEqualTo(aliceId);
    }

    @Test
    void sameChannelUsesOneCanonicalRowWithMultipleRelationships() throws Exception {
        String first = unique("first");
        String second = unique("second");
        register(first, "first password that is long");
        register(second, "second password that is long");
        long firstId = userId(first);
        long secondId = userId(second);
        String channelId = "UC" + UUID.randomUUID().toString().replace("-", "");
        seedChannel(channelId, "One Canonical Channel");
        seedSubscription(firstId, channelId);
        seedSubscription(secondId, channelId);

        assertThat(channels.findById(channelId)).isPresent();
        assertThat(subscriptions.findByChannelIdAndEnabled(channelId, 1)).hasSize(2);
    }

    @Test
    void videosEndpointProjectsDescriptionsStoredAsClobs() throws Exception {
        String username = unique("videos");
        Cookie session = register(username, "videos password that is long");
        long userId = userId(username);
        String channelId = "UC" + UUID.randomUUID().toString().replace("-", "");
        String videoId = "video-" + UUID.randomUUID().toString().replace("-", "");
        seedChannel(channelId, "Video Channel");
        seedSubscription(userId, channelId);
        videos.save(new VideoEntity(videoId, channelId, "Video title", "A description stored in a CLOB",
                "2026-09-17T12:00:00Z", 120, 0, "", "AVAILABLE", ApplicationClock.now()));
        Path libraryPath = paths.usersRoot.resolve(username).resolve(videoId);
        byte[] thumbnail = "thumbnail-bytes".getBytes();
        Files.createDirectories(libraryPath);
        Files.write(libraryPath.resolve("video-thumb.jpg"), thumbnail);
        userVideos.save(new UserVideoEntity(userId, videoId, libraryPath.toString(),
                "playback-token-" + videoId, ApplicationClock.now()));

        mvc.perform(get("/api/videos").cookie(session))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("A description stored in a CLOB"));
        mvc.perform(get("/api/videos/" + videoId + "/thumbnail").cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(thumbnail));
    }

    @Test
    void normalUsersCannotReadOrModifyAdminResources() throws Exception {
        Cookie session = register(unique("normal"), "normal password that is long");
        mvc.perform(get("/api/admin/settings").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/settings").cookie(session).contentType(APPLICATION_JSON)
                        .content("{\"stream_quality\":\"2160\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminsSeeMaskedSecretsAndSettingsRejectUnknownKeys() throws Exception {
        String username = unique("administrator");
        Cookie session = register(username, "administrator password long");
        long userId = userId(username);
        UserEntity admin = users.findById(userId).orElseThrow();
        admin.setRole("ADMIN");
        users.save(admin);
        settings.save("youtube_api_key", "should-not-be-returned", true);
        mvc.perform(get("/api/admin/settings").cookie(session)).andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains(SettingsPolicy.MASK).doesNotContain("should-not-be-returned"));
        mvc.perform(patch("/api/admin/settings").cookie(session).contentType(APPLICATION_JSON)
                        .content("{\"unexpected_secret\":\"value\"}"))
                .andExpect(status().isBadRequest());
    }

    private Cookie register(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax");
        String value = setCookie.split(";", 2)[0];
        return new Cookie("FT_SESSION", value.substring("FT_SESSION=".length()));
    }

    private long userId(String username) throws Exception {
        return users.findByUsername(username).orElseThrow().getId();
    }

    private void seedChannel(String channelId, String name) throws Exception {
        YouTubeChannelEntity channel = channels.findById(channelId)
                .orElseGet(() -> new YouTubeChannelEntity(channelId, name,
                        "https://www.youtube.com/channel/" + channelId, ApplicationClock.now()));
        channel.setName(name);
        channel.setUrl("https://www.youtube.com/channel/" + channelId);
        channel.setUpdatedAt(ApplicationClock.now());
        channels.save(channel);
    }

    private void seedSubscription(long userId, String channelId) throws Exception {
        if (subscriptions.findByUserIdAndChannelIdAndEnabled(userId, channelId, 1).isEmpty()) {
            subscriptions.save(new YouTubeSubscriptionEntity(userId, channelId, 1, ApplicationClock.now()));
        }
    }

    private long subscriptionId(long userId) throws Exception {
        return subscriptions.findAll().stream().filter(value -> value.getUserId() == userId)
                .max(Comparator.comparing(YouTubeSubscriptionEntity::getId))
                .orElseThrow().getId();
    }

    private long subscriptionOwner(long subscriptionId) throws Exception {
        return subscriptions.findById(subscriptionId).orElseThrow().getUserId();
    }

    private String unique(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    private static Path dataDirectory() {
        try { return Files.createTempDirectory("fintube-ownership-"); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
}
