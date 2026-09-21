package ch.it4user.fintube.core;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation and classification for administrator-editable settings.
 * Keeping this policy server-side prevents the UI from becoming a security
 * boundary and makes accidental persistence of arbitrary keys impossible.
 */
public final class SettingsPolicy {
    public static final String MASK = "••••••••";
    private static final Pattern INTEGER = Pattern.compile("[0-9]{1,9}");
    private static final Pattern CODECS = Pattern.compile("[A-Za-z0-9._+,-]{1,200}");
    private static final Set<String> SECRET_KEYS = Set.of(
            "jellyfin_api_key", "proxy_password", "cookie_file", "youtube_watch_cookie_file",
            "youtube_po_token", "youtube_po_token_provider_args");
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "stream_quality", "cache_retention_days", "cache_min_free_gb",
            "background_download_max_mbps", "background_fill_on_playback", "cache_cleanup_interval_minutes", "newest_videos_to_download", "initial_channel_import_count", "initial_short_import_count", "initial_live_stream_import_count",
            "subscription_sync_minutes", "public_base_url", "preferred_video_codecs",
            "preferred_audio_codecs", "jellyfin_url", "jellyfin_api_key",
            "jellyfin_enabled", "jellyfin_auto_refresh", "jellyfin_runtime_sync", "jellyfin_request_timeout_seconds",
            "jellyfin_remove_watched", "jellyfin_watched_user", "youtube_mark_watched",
            "yt_dlp_path", "ffmpeg_path", "proxy_url", "proxy_username", "proxy_password", "cookie_file", "youtube_watch_cookie_file", "youtube_po_token", "youtube_po_token_provider_args", "youtube_po_token_provider_enabled", "youtube_player_client",
            "sponsorblock_enabled", "sponsorblock_api_url",
            "allowed_video_codecs", "allowed_audio_codecs");
    private static final Set<String> QUALITY_VALUES = Set.of("480", "720", "1080", "1440", "2160", "best", "best-compatible");

    private SettingsPolicy() {}

    public static boolean isSecret(String key) { return SECRET_KEYS.contains(key); }

    public static void validate(Map<String, String> values) {
        for (var entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!ALLOWED_KEYS.contains(key)) throw new IllegalArgumentException("unsupported setting: " + key);
            if (value == null || value.isBlank() || MASK.equals(value)) continue;
            switch (key) {
                case "stream_quality" -> require(QUALITY_VALUES.contains(value), key + " must be one of the supported quality values");
                case "cache_retention_days" -> integerInRange(key, value, 1, 3650);
                case "cache_min_free_gb" -> integerInRange(key, value, 0, 1_000_000);
                case "background_download_max_mbps" -> integerInRange(key, value, 1, 1_000_000);
                case "cache_cleanup_interval_minutes" -> integerInRange(key, value, 1, 10080);
                case "newest_videos_to_download" -> integerInRange(key, value, 0, 1000);
                case "initial_channel_import_count", "initial_short_import_count", "initial_live_stream_import_count" -> integerInRange(key, value, 0, 1000);
                case "subscription_sync_minutes" -> integerInRange(key, value, 1, 10080);
                case "background_fill_on_playback", "jellyfin_enabled", "jellyfin_auto_refresh", "jellyfin_runtime_sync", "jellyfin_remove_watched", "youtube_mark_watched", "youtube_po_token_provider_enabled", "sponsorblock_enabled" -> require("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value), key + " must be true or false");
                case "jellyfin_request_timeout_seconds" -> integerInRange(key, value, 1, 120);
                case "public_base_url", "jellyfin_url", "sponsorblock_api_url" -> url(key, value, Set.of("http", "https"));
                case "proxy_url" -> url(key, value, Set.of("http", "https", "socks5"));
                case "preferred_video_codecs", "preferred_audio_codecs", "allowed_video_codecs", "allowed_audio_codecs" -> require(CODECS.matcher(value).matches(), key + " contains invalid codec characters");
                case "youtube_po_token_provider_args", "youtube_player_client", "jellyfin_watched_user" -> require(value.length() <= 4096 && !value.matches(".*[\\r\\n\\u0000].*"), key + " contains invalid characters");
                case "jellyfin_api_key", "proxy_password", "cookie_file", "youtube_watch_cookie_file", "youtube_po_token", "yt_dlp_path", "ffmpeg_path", "proxy_username" -> require(value.length() <= 4096, key + " is too long");
                default -> throw new IllegalArgumentException("unsupported setting: " + key);
            }
        }
    }

    private static void integerInRange(String key, String value, int min, int max) {
        require(INTEGER.matcher(value).matches(), key + " must be an integer");
        try { int parsed = Integer.parseInt(value); require(parsed >= min && parsed <= max, key + " is outside the allowed range"); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " is outside the allowed range"); }
    }

    private static void url(String key, String value, Set<String> schemes) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            require(schemes.contains(scheme) && uri.getHost() != null && uri.getUserInfo() == null,
                    key + " must be an absolute URL without embedded credentials");
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException(key + " must be a valid absolute URL"); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
