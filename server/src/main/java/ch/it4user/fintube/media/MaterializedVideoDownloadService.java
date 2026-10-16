package ch.it4user.fintube.media;

import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.integration.JellyfinSyncService;
import ch.it4user.fintube.persistence.entities.UserVideoEntity;
import ch.it4user.fintube.persistence.repositories.UserVideoRepository;
import ch.it4user.fintube.api.contract.model.MediaPreferences;
import ch.it4user.fintube.service.UserMediaPreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads configured library items as local media files for Jellyfin direct play. */
@Service
public class MaterializedVideoDownloadService {
    private static final Logger LOG = LoggerFactory.getLogger(MaterializedVideoDownloadService.class);
    /** Hidden folder (ignored by Jellyfin) where yt-dlp works before the finished file is moved into place. */
    static final String STAGING_DIRECTORY = ".fintube-download";
    /** Hidden marker (ignored by Jellyfin) for folders whose video is a Jellyfin favorite; kept by {@link #evict}. */
    public static final String FAVORITE_MARKER = ".fintube-favorite";
    private static final String DEFAULT_PO_TOKEN_PROVIDER_ARGS = "youtubepot-bgutilhttp:base_url=http://127.0.0.1:4416";
    private final SettingsService settings;
    private final UserVideoRepository userVideos;
    private final JellyfinSyncService jellyfin;
    private final ProxiedHttpClient externalHttp;
    private final String poTokenProviderUrl;
    private final UserMediaPreferencesService mediaPreferences;
    private final ExecutorService workers = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "fintube-media-downloader"); thread.setDaemon(true); return thread;
    });
    private final ConcurrentHashMap<String, Boolean> queued = new ConcurrentHashMap<>();

    public MaterializedVideoDownloadService(SettingsService settings, UserVideoRepository userVideos,
                                            JellyfinSyncService jellyfin, ProxiedHttpClient externalHttp,
                                            @Value("${fintube.youtube.po-token-provider-url:}") String poTokenProviderUrl,
                                            UserMediaPreferencesService mediaPreferences) {
        this.settings = settings; this.userVideos = userVideos; this.jellyfin = jellyfin;
        this.externalHttp = externalHttp;
        this.poTokenProviderUrl = poTokenProviderUrl;
        this.mediaPreferences = mediaPreferences;
    }

    /** Queue one user's copy. Duplicate requests share the in-flight download. */
    public void enqueue(long userId, String videoId) {
        String key = userId + ":" + videoId;
        if (queued.putIfAbsent(key, Boolean.TRUE) != null) return;
        workers.submit(() -> {
            try { download(userId, videoId); }
            finally { queued.remove(key); }
        });
    }

    private void download(long userId, String videoId) {
        UserVideoEntity link = userVideos.findByIdUserIdAndIdVideoId(userId, videoId).orElse(null);
        if (link == null) return;
        try {
            Path directory = Path.of(link.getLibraryPath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(directory)) return;
            Map<String, String> current = settings.values(false);
            MediaPreferences preferences = preferences(userId);
            Set<String> allowed = allowedVideoCodecs(preferences);
            Path existing = localMedia(directory);
            if (existing != null) {
                // Files from older builds can hold codecs outside the user's list (for example AV1 + Opus).
                // Convert them once when the item is queued again (e.g. by the favorites sync).
                convertExisting(userId, videoId, directory, existing, current, allowed);
                return;
            }
            // yt-dlp writes intermediate streams (video.f137.mp4, *.part, ...) before merging. Keep them in a
            // hidden staging folder so Jellyfin never indexes half-finished files as additional videos.
            Path staging = directory.resolve(STAGING_DIRECTORY);
            deleteTree(staging);
            Files.createDirectories(staging);
            Path template = staging.resolve("video.%(ext)s");
            Process process = new ProcessBuilder(downloadCommand(current, externalHttp.proxyArgument(current),
                    poTokenProviderUrl, template, videoId, preferences))
                    .redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            Path media = localMedia(staging);
            if (exit != 0 || media == null) {
                deleteTree(staging);
                String detail = new String(output).strip();
                if (detail.length() > 500) detail = detail.substring(0, 500) + "…";
                LOG.warn("event=MEDIA_FILE_DOWNLOAD_FAILED userId={} video={} exitCode={} detail={}", userId, videoId, exit, detail);
                return;
            }
            media = ensureDirectPlayable(media, staging, current, userId, videoId, allowed);
            Path canonical = directory.resolve("video." + extension(media));
            Files.move(media, canonical, StandardCopyOption.REPLACE_EXISTING);
            deleteTree(staging);
            // A folder must contain exactly one playable file, otherwise Jellyfin shows the video twice and stops
            // treating the folder as a single movie (losing the local artwork).
            Files.deleteIfExists(directory.resolve("video.strm"));
            DownloadedArtwork.apply(directory, true);
            jellyfin.afterLibraryGeneration(videoId, directory, 0);
            LOG.info("event=MEDIA_FILE_DOWNLOAD_COMPLETED userId={} video={} path={}", userId, videoId, canonical);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            LOG.warn("event=MEDIA_FILE_DOWNLOAD_FAILED userId={} video={} reason={}", userId, videoId, failure.toString());
        }
    }

    private void convertExisting(long userId, String videoId, Path directory, Path existing,
                                 Map<String, String> current, Set<String> allowed) throws IOException, InterruptedException {
        Path staging = directory.resolve(STAGING_DIRECTORY);
        deleteTree(staging);
        Files.createDirectories(staging);
        try {
            Path converted = ensureDirectPlayable(existing, staging, current, userId, videoId, allowed);
            if (converted.equals(existing)) return;
            Path canonical = directory.resolve("video.mp4");
            Files.move(converted, canonical, StandardCopyOption.REPLACE_EXISTING);
            if (!existing.equals(canonical)) Files.deleteIfExists(existing);
            jellyfin.afterLibraryGeneration(videoId, directory, 0);
            LOG.info("event=MEDIA_FILE_CONVERTED userId={} video={} path={}", userId, videoId, canonical);
        } finally {
            deleteTree(staging);
        }
    }

    private MediaPreferences preferences(long userId) {
        try { return mediaPreferences == null ? null : mediaPreferences.get(userId); }
        catch (RuntimeException unavailable) { return null; }
    }

    /** Video codecs the user accepts for direct play, restricted to those FinTube can store in MP4. */
    static Set<String> allowedVideoCodecs(MediaPreferences preferences) {
        String value = preferences == null || preferences.getPreferredVideoCodecs() == null
                ? UserMediaPreferencesService.DEFAULT_VIDEO_CODECS : preferences.getPreferredVideoCodecs();
        Set<String> result = new LinkedHashSet<>();
        for (String codec : value.toLowerCase(Locale.ROOT).split(",")) {
            String name = codec.trim();
            if (name.startsWith("avc") || name.equals("h264")) result.add("h264");
            else if (name.startsWith("vp9") || name.startsWith("vp09")) result.add("vp9");
            else if (name.startsWith("av1") || name.startsWith("av01")) result.add("av1");
        }
        if (result.isEmpty()) result.add("h264");
        return result;
    }

    /**
     * Makes sure the file direct-plays with the user's codecs: an 8-bit 4:2:0 video codec from the user's list
     * (H.264, VP9 or AV1), AAC audio, MP4 container. Streams that already comply are copied, only the others are
     * re-encoded (video to H.264, audio to AAC). Returns the converted file inside {@code workDir}, or
     * {@code media} unchanged when it already complies or the conversion failed.
     */
    private Path ensureDirectPlayable(Path media, Path workDir, Map<String, String> settings,
                                      long userId, String videoId, Set<String> allowed) throws IOException, InterruptedException {
        String ffmpeg = settings.getOrDefault("ffmpeg_path", "").trim();
        if (ffmpeg.isBlank()) ffmpeg = "ffmpeg";
        StreamInfo info = probe(ffprobePath(ffmpeg), media);
        if (info == null) {
            LOG.warn("event=MEDIA_FILE_PROBE_FAILED userId={} video={} path={}", userId, videoId, media);
            return media;
        }
        if (info.directPlayable(allowed) && "mp4".equalsIgnoreCase(extension(media))) return media;
        Path output = workDir.resolve("video.compat.mp4");
        Files.deleteIfExists(output);
        LOG.info("event=MEDIA_FILE_TRANSCODE_STARTED userId={} video={} videoCodec={} pixelFormat={} audioCodec={}",
                userId, videoId, info.videoCodec(), info.pixelFormat(), info.audioCodec());
        Process process = new ProcessBuilder(compatibilityCommand(ffmpeg, media, output, info, allowed))
                .redirectErrorStream(true).start();
        byte[] log = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
            Files.deleteIfExists(output);
            String detail = new String(log, StandardCharsets.UTF_8).strip();
            if (detail.length() > 500) detail = detail.substring(0, 500) + "…";
            LOG.warn("event=MEDIA_FILE_TRANSCODE_FAILED userId={} video={} exitCode={} detail={}",
                    userId, videoId, exit, detail);
            return media;
        }
        LOG.info("event=MEDIA_FILE_TRANSCODE_COMPLETED userId={} video={} bytes={}", userId, videoId, Files.size(output));
        return output;
    }

    /** Codecs of the first video and audio stream as reported by ffprobe; empty when the stream is absent. */
    record StreamInfo(String videoCodec, String pixelFormat, String audioCodec) {
        boolean videoCompatible() { return videoCompatible(Set.of("h264")); }

        boolean videoCompatible(Set<String> allowed) {
            // H.264 is also the re-encode target, so an 8-bit H.264 stream is never re-encoded.
            return (allowed.contains(videoCodec) || "h264".equals(videoCodec))
                    && ("yuv420p".equals(pixelFormat) || "yuvj420p".equals(pixelFormat));
        }

        boolean audioCompatible() { return audioCodec.isEmpty() || "aac".equals(audioCodec); }

        boolean directPlayable() { return directPlayable(Set.of("h264")); }

        boolean directPlayable(Set<String> allowed) { return videoCompatible(allowed) && audioCompatible(); }
    }

    private static StreamInfo probe(String ffprobe, Path media) throws InterruptedException {
        try {
            Process process = new ProcessBuilder(ffprobe, "-v", "error",
                    "-show_entries", "stream=codec_type,codec_name,pix_fmt", "-of", "compact=p=0", media.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() == 0 ? parseStreams(output) : null;
        } catch (IOException unavailable) {
            return null;
        }
    }

    /** Parses {@code ffprobe -of compact=p=0} output, one {@code key=value|key=value} line per stream. */
    static StreamInfo parseStreams(String output) {
        String videoCodec = null, pixelFormat = "", audioCodec = null;
        for (String line : output.strip().split("\\R")) {
            Map<String, String> fields = new HashMap<>();
            for (String field : line.split("\\|")) {
                int separator = field.indexOf('=');
                if (separator > 0) fields.put(field.substring(0, separator).trim(), field.substring(separator + 1).trim());
            }
            String type = fields.getOrDefault("codec_type", "");
            String codec = fields.getOrDefault("codec_name", "");
            if (type.equals("video") && videoCodec == null) {
                videoCodec = codec;
                pixelFormat = fields.getOrDefault("pix_fmt", "");
            } else if (type.equals("audio") && audioCodec == null) {
                audioCodec = codec;
            }
        }
        return videoCodec == null ? null : new StreamInfo(videoCodec, pixelFormat, audioCodec == null ? "" : audioCodec);
    }

    /** ffmpeg command that copies compliant streams and re-encodes the rest to H.264 High / AAC in MP4. */
    static List<String> compatibilityCommand(String ffmpeg, Path input, Path output, StreamInfo info) {
        return compatibilityCommand(ffmpeg, input, output, info, Set.of("h264"));
    }

    static List<String> compatibilityCommand(String ffmpeg, Path input, Path output, StreamInfo info, Set<String> allowed) {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
                "-y", "-i", input.toString(), "-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "0"));
        if (info.videoCompatible(allowed)) command.addAll(List.of("-c:v", "copy"));
        else command.addAll(List.of("-c:v", "libx264", "-preset", "medium", "-crf", "18",
                "-profile:v", "high", "-pix_fmt", "yuv420p"));
        if (info.audioCompatible()) command.addAll(List.of("-c:a", "copy"));
        else command.addAll(List.of("-c:a", "aac", "-b:a", "192k", "-ac", "2"));
        command.addAll(List.of("-movflags", "+faststart", output.toString()));
        return command;
    }

    private static String ffprobePath(String ffmpeg) {
        Path name = Path.of(ffmpeg).getFileName();
        if (name != null && name.toString().equals("ffmpeg")) return Path.of(ffmpeg).resolveSibling("ffprobe").toString();
        return "ffprobe";
    }

    /** Builds the yt-dlp command with the same YouTube access settings as source probes. */
    static List<String> downloadCommand(Map<String, String> settings, String proxy, String poTokenProviderUrl,
                                        Path template, String videoId) {
        return downloadCommand(settings, proxy, poTokenProviderUrl, template, videoId, null);
    }

    static List<String> downloadCommand(Map<String, String> settings, String proxy, String poTokenProviderUrl,
                                        Path template, String videoId, MediaPreferences preferences) {
        List<String> command = new ArrayList<>();
        command.add(settings.getOrDefault("yt_dlp_path", "yt-dlp"));
        command.addAll(List.of("--no-playlist", "--no-progress", "--no-warnings",
                "-f", formatSelector(preferences, settings),
                "-S", formatSort(preferences),
                "--merge-output-format", "mp4", "--remux-video", "mp4"));
        if (proxy != null && !proxy.isBlank()) command.addAll(List.of("--proxy", proxy));
        String cookieFile = settings.getOrDefault("cookie_file", "").trim();
        if (!cookieFile.isBlank()) command.addAll(List.of("--cookies", cookieFile));
        String playerClient = settings.getOrDefault("youtube_player_client", "").trim();
        if (!playerClient.isBlank()) command.addAll(List.of("--extractor-args", "youtube:player_client=" + playerClient));
        String poToken = settings.getOrDefault("youtube_po_token", "").trim();
        if (!poToken.isBlank()) command.addAll(List.of("--extractor-args", "youtube:po_token=" + poToken));
        String providerArgs = providerArguments(settings, poTokenProviderUrl);
        if (!providerArgs.isBlank()) command.addAll(List.of("--extractor-args", providerArgs));
        command.addAll(List.of("-o", template.toString(), "https://www.youtube.com/watch?v=" + videoId));
        return command;
    }

    /**
     * Best video within the user's quality ceiling and codec list with AAC audio (no audio conversion needed).
     * Falls back to any audio, then to any format, so a download never fails only because of codecs; such files
     * are converted afterwards by ensureDirectPlayable.
     */
    static String formatSelector(MediaPreferences preferences, Map<String, String> settings) {
        String height = heightFilter(preferences, settings);
        StringBuilder codecs = new StringBuilder();
        for (String codec : allowedVideoCodecs(preferences)) {
            if (codecs.length() > 0) codecs.append('|');
            codecs.append(switch (codec) { case "vp9" -> "vp0?9"; case "av1" -> "av01"; default -> "avc1|h264"; });
        }
        String video = "bv*" + height + "[vcodec~='^(" + codecs + ")']";
        return video + "+ba[acodec^=mp4a]/" + video + "+ba/b" + height + "/bv*+ba/b";
    }

    /** Resolution first, then frame rate and SDR; the user's first codec breaks ties. */
    static String formatSort(MediaPreferences preferences) {
        String first = allowedVideoCodecs(preferences).iterator().next();
        String codec = switch (first) { case "vp9" -> "vp9"; case "av1" -> "av01"; default -> "h264"; };
        return "res,fps,hdr:SDR,vcodec:" + codec + ",acodec:aac";
    }

    private static String heightFilter(MediaPreferences preferences, Map<String, String> settings) {
        String quality = preferences == null || preferences.getQuality() == null
                ? settings.getOrDefault("stream_quality", UserMediaPreferencesService.DEFAULT_QUALITY)
                : preferences.getQuality().getValue();
        if (quality == null) return "";
        try { return "[height<=" + Integer.parseInt(quality.trim()) + "]"; }
        catch (NumberFormatException best) { return quality.trim().equals("best-compatible") ? "[dynamic_range=SDR]" : ""; }
    }

    private static String providerArguments(Map<String, String> settings, String poTokenProviderUrl) {
        if (!"true".equalsIgnoreCase(settings.getOrDefault("youtube_po_token_provider_enabled", "true"))) return "";
        if (poTokenProviderUrl != null && !poTokenProviderUrl.isBlank()) {
            return "youtubepot-bgutilhttp:base_url=" + poTokenProviderUrl;
        }
        return settings.getOrDefault("youtube_po_token_provider_args", DEFAULT_PO_TOKEN_PROVIDER_ARGS);
    }

    /** Sets or clears the favorite marker of a library folder. */
    public static void markFavorite(Path directory, boolean favorite) throws IOException {
        if (!Files.isDirectory(directory)) return;
        Path marker = directory.resolve(FAVORITE_MARKER);
        if (favorite) { if (!Files.exists(marker)) Files.createFile(marker); }
        else Files.deleteIfExists(marker);
    }

    /**
     * Deletes the downloaded media of a library folder so it plays via .strm again. Favorites are kept.
     * Returns {@code true} when a file was removed.
     */
    public static boolean evict(Path directory) throws IOException {
        if (!Files.isDirectory(directory) || Files.exists(directory.resolve(FAVORITE_MARKER))) return false;
        boolean removed = false;
        for (Path media = localMedia(directory); media != null; media = localMedia(directory)) {
            Files.delete(media);
            removed = true;
        }
        return removed;
    }

    /** Returns the downloaded media file of a library folder, or {@code null} while it still plays via .strm. */
    public static Path localMedia(Path directory) throws java.io.IOException {
        if (!Files.isDirectory(directory)) return null;
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("video\\.(mp4|mkv|webm|m4v|mov)"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .findFirst().orElse(null);
        }
    }

    private static void deleteTree(Path path) throws java.io.IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(path)) {
            for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
        }
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
