package ch.it4user.fintube.service;

import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.persistence.entities.UserEntity;
import ch.it4user.fintube.persistence.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserYouTubeApiKeyService {
    private static final int MAX_KEY_LENGTH = 4096;

    private final UserRepository users;
    private final SettingsService settings;

    public UserYouTubeApiKeyService(UserRepository users, SettingsService settings) {
        this.users = users;
        this.settings = settings;
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

    private UserEntity user(long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
