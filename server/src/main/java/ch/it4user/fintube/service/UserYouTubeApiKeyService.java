package ch.it4user.fintube.service;

import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.core.ApplicationPaths;
import ch.it4user.fintube.persistence.entities.UserEntity;
import ch.it4user.fintube.persistence.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

@Service
public class UserYouTubeApiKeyService {
    private static final int MAX_KEY_LENGTH = 4096;
    private static final long MAX_COOKIE_BYTES = 5_000_000;

    private final UserRepository users;
    private final SettingsService settings;
    private final ApplicationPaths paths;

    public UserYouTubeApiKeyService(UserRepository users, SettingsService settings, ApplicationPaths paths) {
        this.users = users;
        this.settings = settings;
        this.paths = paths;
    }

    @Transactional(readOnly = true)
    public String key(long userId) {
        UserEntity user = user(userId);
        String stored = user.getYoutubeApiKey();
        return stored == null || stored.isBlank() ? null : settings.reveal(stored);
    }

    @Transactional(readOnly = true)
    public boolean configured(long userId) {
        return key(userId) != null;
    }

    @Transactional
    public void update(long userId, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > MAX_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "YouTube API key is too long");
        }
        UserEntity user = user(userId);
        user.setYoutubeApiKey(normalized.isBlank() ? null : settings.protect(normalized));
        users.save(user);
    }

    @Transactional(readOnly = true)
    public String watchCookie(long userId) {
        String stored = user(userId).getYoutubeWatchCookiePath();
        return stored == null || stored.isBlank() ? null : settings.reveal(stored);
    }

    @Transactional(readOnly = true)
    public boolean watchCookieConfigured(long userId) {
        String path = watchCookie(userId);
        return path != null && Files.isRegularFile(Path.of(path));
    }

    @Transactional
    public void uploadWatchCookie(long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a cookies.txt file first");
        if (file.getSize() > MAX_COOKIE_BYTES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cookie file must be 5 MB or smaller");
        byte[] contents;
        try { contents = file.getBytes(); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded cookie file"); }
        if (!new String(contents, StandardCharsets.UTF_8).contains("Netscape HTTP Cookie File")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a Netscape-format cookies.txt file");
        }
        Path secrets = paths.root.resolve("secrets").toAbsolutePath().normalize();
        Path destination = secrets.resolve("youtube-watch-history-cookies-" + userId + ".txt");
        try {
            Files.createDirectories(secrets);
            Path temporary = Files.createTempFile(secrets, "youtube-watch-history-", ".tmp");
            try {
                Files.write(temporary, contents);
                try { Files.setPosixFilePermissions(temporary, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
                catch (UnsupportedOperationException ignored) { }
                try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store uploaded cookie file");
        }
        UserEntity user = user(userId);
        user.setYoutubeWatchCookiePath(settings.protect(destination.toString()));
        users.save(user);
    }

    private UserEntity user(long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
