package ch.it4user.fintube.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsPolicyTest {
    @Test
    void acceptsSupportedValuesAndClassifiesSecrets() {
        SettingsPolicy.validate(Map.ofEntries(
                Map.entry("stream_quality", "1080"),
                Map.entry("cache_retention_days", "30"),
                Map.entry("cache_cleanup_interval_minutes", "360"),
                Map.entry("background_fill_on_playback", "false"),
                Map.entry("jellyfin_remove_watched", "true"),
                Map.entry("jellyfin_watched_user", "Eric"),
                Map.entry("youtube_mark_watched", "true"),
                Map.entry("youtube_watch_cookie_file", "/data/youtube-history.cookies.txt"),
                Map.entry("public_base_url", "https://bridge.example.test"),
                Map.entry("jellyfin_url", "http://jellyfin:8096"),
                Map.entry("jellyfin_request_timeout_seconds", "10"),
                Map.entry("jellyfin_api_key", "api-key")));

        assertThat(SettingsPolicy.isSecret("jellyfin_api_key")).isTrue();
        assertThat(SettingsPolicy.isSecret("youtube_watch_cookie_file")).isTrue();
        assertThat(SettingsPolicy.isSecret("stream_quality")).isFalse();
    }

    @Test
    void rejectsUnknownKeysInvalidRangesAndEmbeddedCredentials() {
        assertThatThrownBy(() -> SettingsPolicy.validate(Map.of("not_a_setting", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingsPolicy.validate(Map.of("youtube_api_key", "legacy-key")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingsPolicy.validate(Map.of("stream_quality", "999")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingsPolicy.validate(Map.of("cache_retention_days", "0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingsPolicy.validate(Map.of("background_fill_on_playback", "sometimes")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingsPolicy.validate(Map.of("public_base_url", "https://user:password@example.test")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsMaskedSecretsToBeSentBackWithoutOverwritingThem() {
        SettingsPolicy.validate(Map.of("jellyfin_api_key", SettingsPolicy.MASK));
    }
}
