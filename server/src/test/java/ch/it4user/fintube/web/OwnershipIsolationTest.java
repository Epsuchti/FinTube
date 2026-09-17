package ch.it4user.fintube.web;

import ch.it4user.fintube.core.Database;
import ch.it4user.fintube.core.SettingsPolicy;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    @Autowired Database database;

    @DynamicPropertySource
    static void dataProperties(DynamicPropertyRegistry registry) {
        registry.add("fintube.data-dir", TEST_DATA_DIR::toString);
    }

    @Test
    void registrationsHashPasswordsAndCreatePrivateFilesystemRoots() throws Exception {
        String username = unique("path");
        Cookie session = register(username, "correct horse battery staple");

        assertThat(session.getValue()).isNotBlank();
        try (var c = database.open(); var p = c.prepareStatement("SELECT password_hash,filesystem_slug FROM users WHERE username=?")) {
            p.setString(1, username);
            try (ResultSet result = p.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("password_hash")).doesNotContain("correct horse battery staple");
                String slug = result.getString("filesystem_slug");
                assertThat(slug).matches("[a-z0-9]+(?:-[a-z0-9]+)*");
                assertThat(Files.isDirectory(database.usersRoot.resolve(slug))).isTrue();
                assertThat(database.usersRoot.resolve(slug).normalize().startsWith(database.usersRoot.normalize())).isTrue();
            }
        }

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
        seedChannel("UC" + UUID.randomUUID().toString().replace("-", ""), "Shared Channel");
        String channelId = latestChannelId();
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

        try (var c = database.open(); var p = c.prepareStatement("SELECT count(*) FROM youtube_channels WHERE channel_id=?")) {
            p.setString(1, channelId);
            try (ResultSet result = p.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
        try (var c = database.open(); var p = c.prepareStatement("SELECT count(*) FROM youtube_subscriptions WHERE channel_id=?")) {
            p.setString(1, channelId);
            try (ResultSet result = p.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(2);
            }
        }
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
        try (var c = database.open(); var role = c.prepareStatement("UPDATE users SET role='ADMIN' WHERE id=?")) {
            role.setLong(1, userId);
            role.executeUpdate();
            try (var secret = c.prepareStatement("MERGE INTO settings(key,value,secret,updated_at) KEY(key) VALUES(?,?,1,?)")) {
                secret.setString(1, "youtube_api_key");
                secret.setString(2, "should-not-be-returned");
                secret.setString(3, Database.now());
                secret.executeUpdate();
            }
        }
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
        try (var c = database.open(); var p = c.prepareStatement("SELECT id FROM users WHERE username=?")) {
            p.setString(1, username);
            try (ResultSet result = p.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private void seedChannel(String channelId, String name) throws Exception {
        try (var c = database.open(); var p = c.prepareStatement("MERGE INTO youtube_channels(channel_id,name,url,updated_at) KEY(channel_id) VALUES(?,?,?,?)")) {
            p.setString(1, channelId);
            p.setString(2, name);
            p.setString(3, "https://www.youtube.com/channel/" + channelId);
            p.setString(4, Database.now());
            p.executeUpdate();
        }
    }

    private void seedSubscription(long userId, String channelId) throws Exception {
        try (var c = database.open(); var p = c.prepareStatement("MERGE INTO youtube_subscriptions(user_id,channel_id,created_at) KEY(user_id,channel_id) VALUES(?,?,?)")) {
            p.setLong(1, userId);
            p.setString(2, channelId);
            p.setString(3, Database.now());
            p.executeUpdate();
        }
    }

    private String latestChannelId() throws Exception {
        try (var c = database.open(); var p = c.prepareStatement("SELECT channel_id FROM youtube_channels ORDER BY updated_at DESC LIMIT 1"); ResultSet result = p.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private long subscriptionId(long userId) throws Exception {
        try (var c = database.open(); var p = c.prepareStatement("SELECT id FROM youtube_subscriptions WHERE user_id=? ORDER BY id DESC LIMIT 1")) {
            p.setLong(1, userId);
            try (ResultSet result = p.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private long subscriptionOwner(long subscriptionId) throws Exception {
        try (var c = database.open(); var p = c.prepareStatement("SELECT user_id FROM youtube_subscriptions WHERE id=?")) {
            p.setLong(1, subscriptionId);
            try (ResultSet result = p.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private String unique(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    private static Path dataDirectory() {
        try { return Files.createTempDirectory("fintube-ownership-"); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
}
