package ch.it4user.fintube.service;

import ch.it4user.fintube.api.contract.model.MediaPreferences;
import ch.it4user.fintube.core.ApplicationClock;
import ch.it4user.fintube.persistence.entities.UserEntity;
import ch.it4user.fintube.persistence.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class UserMediaPreferencesService {
    public static final String DEFAULT_QUALITY = "1080";
    public static final String DEFAULT_VIDEO_CODECS = "h264,vp9,av1";
    public static final String DEFAULT_AUDIO_CODECS = "aac,opus";
    private static final Set<String> QUALITIES = Set.of("480", "720", "1080", "1440", "2160", "best", "best-compatible");
    private static final Pattern CODECS = Pattern.compile("[A-Za-z0-9._+,-]{1,200}");
    private final UserRepository users;

    public UserMediaPreferencesService(UserRepository users) { this.users = users; }

    public MediaPreferences get(long userId) { return toModel(user(userId)); }

    @Transactional
    public MediaPreferences update(long userId, MediaPreferences input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "media preferences are required");
        String quality = normalizedQuality(input.getQuality() == null ? null : input.getQuality().getValue());
        String video = normalizedCodecs(input.getPreferredVideoCodecs(), "preferred_video_codecs");
        String audio = normalizedCodecs(input.getPreferredAudioCodecs(), "preferred_audio_codecs");
        UserEntity user = user(userId);
        user.setMediaQuality(quality); user.setPreferredVideoCodecs(video); user.setPreferredAudioCodecs(audio);
        user.setUpdatedAt(ApplicationClock.now()); users.save(user);
        return toModel(user);
    }

    private UserEntity user(long id) { return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)); }
    private static MediaPreferences toModel(UserEntity user) {
        return new MediaPreferences(MediaPreferences.QualityEnum.fromValue(normalizedQuality(user.getMediaQuality())),
            fallback(user.getPreferredVideoCodecs(), DEFAULT_VIDEO_CODECS), fallback(user.getPreferredAudioCodecs(), DEFAULT_AUDIO_CODECS));
    }
    private static String normalizedQuality(String value) {
        String quality = fallback(value, DEFAULT_QUALITY).toLowerCase(Locale.ROOT);
        if (!QUALITIES.contains(quality)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quality must be a supported value");
        return quality;
    }
    private static String normalizedCodecs(String value, String field) {
        String codecs = fallback(value, "").toLowerCase(Locale.ROOT);
        if (!CODECS.matcher(codecs).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " contains invalid codec characters");
        return codecs;
    }
    private static String fallback(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
}
