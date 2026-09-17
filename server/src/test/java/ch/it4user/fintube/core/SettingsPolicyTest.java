package ch.it4user.fintube.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsPolicyTest {
    @Test
    void acceptsSupportedValuesAndClassifiesSecrets() {
        SettingsPolicy.validate(Map.of(
                "stream_quality", "1080",
                "cache_retention_days", "30",
                "cache_cleanup_interval_minutes", "360",
                "public_base_url", "https://bridge.example.test",
                "jellyfin_url", "http://jellyfin:8096",
                "jellyfin_request_timeout_seconds", "10",
                "youtube_api_key", "api-key"));

        assertThat(SettingsPolicy.isSecret("youtube_api_key")).isTrue();
        assertThat(SettingsPolicy.isSecret("stream_quality")).isFalse();
    }

    @Test
    void rejectsUnknownKeysInvalidRangesAndEmbeddedCredentials() {
        assertThatThrownBy(() -> SettingsPolicy.validate(Map.of("not_a_setting", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingsPolicy.validate(Map.of("stream_quality", "999")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingsPolicy.validate(Map.of("cache_retention_days", "0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingsPolicy.validate(Map.of("public_base_url", "https://user:password@example.test")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsMaskedSecretsToBeSentBackWithoutOverwritingThem() {
        SettingsPolicy.validate(Map.of("youtube_api_key", SettingsPolicy.MASK, "jellyfin_api_key", SettingsPolicy.MASK));
    }
}
