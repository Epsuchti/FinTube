package ch.it4user.fintube.media;

import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.integration.JellyfinSyncService;
import ch.it4user.fintube.persistence.entities.UserVideoEntity;
import ch.it4user.fintube.persistence.repositories.UserVideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads configured library items as local media files for Jellyfin direct play. */
@Service
public class MaterializedVideoDownloadService {
    private static final Logger LOG = LoggerFactory.getLogger(MaterializedVideoDownloadService.class);
    private static final String DEFAULT_PO_TOKEN_PROVIDER_ARGS = "youtubepot-bgutilhttp:base_url=http://127.0.0.1:4416";
    private final SettingsService settings;
    private final UserVideoRepository userVideos;
    private final JellyfinSyncService jellyfin;
    private final ProxiedHttpClient externalHttp;
    private final String poTokenProviderUrl;
    private final ExecutorService workers = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "fintube-media-downloader"); thread.setDaemon(true); return thread;
    });
    private final ConcurrentHashMap<String, Boolean> queued = new ConcurrentHashMap<>();

    public MaterializedVideoDownloadService(SettingsService settings, UserVideoRepository userVideos,
                                            JellyfinSyncService jellyfin, ProxiedHttpClient externalHttp,
                                            @Value("${fintube.youtube.po-token-provider-url:}") String poTokenProviderUrl) {
        this.settings = settings; this.userVideos = userVideos; this.jellyfin = jellyfin;
        this.externalHttp = externalHttp;
        this.poTokenProviderUrl = poTokenProviderUrl;
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
            if (localMedia(directory) != null) return;
            Map<String, String> current = settings.values(false);
            Path template = directory.resolve("video.%(ext)s");
            Process process = new ProcessBuilder(downloadCommand(current, externalHttp.proxyArgument(current),
                    poTokenProviderUrl, template, videoId))
                    .redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            Path media = localMedia(directory);
            if (exit != 0 || media == null) {
                String detail = new String(output).strip();
                if (detail.length() > 500) detail = detail.substring(0, 500) + "…";
                LOG.warn("event=MEDIA_FILE_DOWNLOAD_FAILED userId={} video={} exitCode={} detail={}", userId, videoId, exit, detail);
                return;
            }
            Path canonical = directory.resolve("video." + extension(media));
            if (!media.equals(canonical)) Files.move(media, canonical, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(directory.resolve("video.strm"));
            jellyfin.afterLibraryGeneration(videoId, directory, 0);
            LOG.info("event=MEDIA_FILE_DOWNLOAD_COMPLETED userId={} video={} path={}", userId, videoId, canonical);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            LOG.warn("event=MEDIA_FILE_DOWNLOAD_FAILED userId={} video={} reason={}", userId, videoId, failure.toString());
        }
    }

    /** Builds the yt-dlp command with the same YouTube access settings as source probes. */
    static List<String> downloadCommand(Map<String, String> settings, String proxy, String poTokenProviderUrl,
                                        Path template, String videoId) {
        List<String> command = new ArrayList<>();
        command.add(settings.getOrDefault("yt_dlp_path", "yt-dlp"));
        command.addAll(List.of("--no-playlist", "--no-progress", "--no-warnings",
                "-f", "bv*+ba/b", "--merge-output-format", "mp4", "--remux-video", "mp4"));
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

    private static String providerArguments(Map<String, String> settings, String poTokenProviderUrl) {
        if (!"true".equalsIgnoreCase(settings.getOrDefault("youtube_po_token_provider_enabled", "true"))) return "";
        if (poTokenProviderUrl != null && !poTokenProviderUrl.isBlank()) {
            return "youtubepot-bgutilhttp:base_url=" + poTokenProviderUrl;
        }
        return settings.getOrDefault("youtube_po_token_provider_args", DEFAULT_PO_TOKEN_PROVIDER_ARGS);
    }

    private static Path localMedia(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("video\\.(mp4|mkv|webm|m4v|mov)"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .findFirst().orElse(null);
        }
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
