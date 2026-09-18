package ch.it4user.fintube.core;

import ch.it4user.fintube.persistence.entities.SetupStateEntity;
import ch.it4user.fintube.persistence.repositories.SetupStateRepository;
import ch.it4user.fintube.persistence.entities.UserEntity;
import ch.it4user.fintube.persistence.repositories.UserRepository;
import ch.it4user.fintube.service.AuthService;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Provisions a one-time credential; it never creates or overwrites an administrator itself. */
@Component
@org.springframework.context.annotation.DependsOn("liquibase")
public class Bootstrap {
    private static final String TOKEN_KEY = "admin_token_hash";
    private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class);

    private final ApplicationPaths paths;
    private final SetupStateRepository setupState;
    private final UserRepository users;
    private final AuthService auth;

    Bootstrap(ApplicationPaths paths, SetupStateRepository setupState, UserRepository users, AuthService auth) {
        this.paths = paths;
        this.setupState = setupState;
        this.users = users;
        this.auth = auth;
    }

    @PostConstruct
    void prepareSetup() throws Exception {
        ensureToken();
    }

    @Transactional
    synchronized void ensureToken() throws Exception {
        if (hasAdmin()) return;
        if (tokenHash() != null) {
            logLocalToken();
            return;
        }

        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        writeLocalToken(token);
        LOG.info("No administrator exists. Use this one-time setup token in the setup wizard: {}", token);
        setupState.save(new SetupStateEntity(TOKEN_KEY, auth.hash(token), ApplicationClock.now()));
    }

    public boolean required() {
        try {
            boolean missing = !hasAdmin();
            if (missing && tokenHash() == null) ensureToken();
            return missing;
        } catch (Exception e) {
            return true;
        }
    }

    @Transactional
    public long createAdmin(String username, String email, String password, String token) throws Exception {
        if (!required()) throw new IllegalStateException("an administrator already exists");
        String hash = tokenHash();
        if (hash == null || !auth.matches(token, hash)) throw new SecurityException("invalid setup token");
        if (!username.matches("[A-Za-z0-9_.-]{3,48}") || password.length() < 12) {
            throw new IllegalArgumentException("username or password does not meet policy");
        }

        String slug = auth.slug(username);
        String now = ApplicationClock.now();
        UserEntity user = users.save(new UserEntity(username,
                email == null || email.isBlank() ? null : email,
                auth.hash(password), "ADMIN", slug, now, now));
        Files.createDirectories(paths.usersRoot.resolve(slug));
        setupState.deleteById(TOKEN_KEY);
        Files.deleteIfExists(paths.root.resolve("setup-admin-token"));
        return user.getId();
    }

    private boolean hasAdmin() {
        return users.existsByRole("ADMIN");
    }

    private String tokenHash() {
        return setupState.findById(TOKEN_KEY).map(SetupStateEntity::getValue).orElse(null);
    }

    private void writeLocalToken(String token) throws Exception {
        Path file = paths.root.resolve("setup-admin-token");
        Files.writeString(file, token + "\n");
        try {
            Files.setPosixFilePermissions(file,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows and other filesystems may not expose POSIX permissions.
        }
    }

    private void logLocalToken() {
        try {
            String token = Files.readString(paths.root.resolve("setup-admin-token")).trim();
            if (!token.isBlank()) {
                LOG.info("No administrator exists. Use this one-time setup token in the setup wizard: {}", token);
            }
        } catch (Exception ignored) {
        }
    }
}
