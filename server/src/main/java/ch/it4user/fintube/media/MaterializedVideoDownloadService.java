package ch.it4user.fintube.media;

import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.integration.JellyfinSyncService;
import ch.it4user.fintube.persistence.entities.UserVideoEntity;
import ch.it4user.fintube.persistence.repositories.UserVideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads configured library items as local media files for Jellyfin direct play. */
@Service
public class MaterializedVideoDownloadService {
    private static final Logger LOG = LoggerFactory.getLogger(MaterializedVideoDownloadService.class);
    private final SettingsService settings;
    private final UserVideoRepository userVideos;
    private final JellyfinSyncService jellyfin;
    private final ExecutorService workers = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "fintube-media-downloader"); thread.setDaemon(true); return thread;
    });
    private final ConcurrentHashMap<String, Boolean> queued = new ConcurrentHashMap<>();

    public MaterializedVideoDownloadService(SettingsService settings, UserVideoRepository userVideos,
                                            JellyfinSyncService jellyfin) {
        this.settings = settings; this.userVideos = userVideos; this.jellyfin = jellyfin;
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
            String executable = settings.value("yt_dlp_path");
            if (executable == null || executable.isBlank()) executable = "yt-dlp";
            Path template = directory.resolve("video.%(ext)s");
            Process process = new ProcessBuilder(executable, "--no-playlist", "--no-progress", "--no-warnings",
                    "-f", "bv*+ba/b", "--merge-output-format", "mp4", "--remux-video", "mp4",
                    "-o", template.toString(), "https://www.youtube.com/watch?v=" + videoId)
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
