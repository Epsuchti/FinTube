package ch.it4user.fintube.service;

import ch.it4user.fintube.media.BackgroundFillService;
import ch.it4user.fintube.media.FragmentManager;
import ch.it4user.fintube.media.MediaSourceService;
import ch.it4user.fintube.persistence.repositories.UserVideoRepository;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Application service for stable Jellyfin playback and shared fragment access. */
@Service
public class PlaybackService {
    private final UserVideoRepository userVideos;
    private final MediaSourceService sources;
    private final FragmentManager fragments;
    private final BackgroundFillService filler;

    public PlaybackService(UserVideoRepository userVideos, MediaSourceService sources, FragmentManager fragments, BackgroundFillService filler) {
        this.userVideos = userVideos;
        this.sources = sources;
        this.fragments = fragments;
        this.filler = filler;
    }

    public String manifest(String video, String token) {
        try {
            valid(video, token);
            var source = sources.source(video);
            filler.enqueue(video);
            StringBuilder output = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n");
            output.append("#EXT-X-TARGETDURATION:").append(source.targetDuration()).append("\n#EXT-X-MEDIA-SEQUENCE:0\n");
            for (var fragment : source.fragments()) {
                output.append("#EXTINF:").append(String.format(java.util.Locale.ROOT, "%.3f", fragment.seconds())).append(",\n/play/")
                        .append(video).append("/fragment/").append(fragment.id()).append("?token=").append(token).append("\n");
            }
            return output.append("#EXT-X-ENDLIST\n").toString();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "playback manifest could not be resolved", e);
        }
    }

    public Fragment fragment(String video, String fragmentId, String token) {
        try {
            valid(video, token);
            var source = sources.source(video);
            var sourceFragment = source.fragments().stream().filter(item -> item.id().equals(fragmentId)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            Path path;
            InputStream stream;
            try {
                path = fragments.get(video, source.format(), fragmentId, sourceFragment.url(), sourceFragment.audioUrl(), true);
                stream = fragments.open(video, source.format(), fragmentId, sourceFragment.url(), sourceFragment.audioUrl(), true);
            } catch (FragmentManager.ExpiredSourceException e) {
                source = sources.refresh(video);
                var refreshed = source.fragments().stream().filter(item -> item.id().equals(fragmentId)).findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY));
                path = fragments.get(video, source.format(), fragmentId, refreshed.url(), refreshed.audioUrl(), true);
                stream = fragments.open(video, source.format(), fragmentId, refreshed.url(), refreshed.audioUrl(), true);
            }
            MediaType type = source.progressive()
                    ? ("webm".equalsIgnoreCase(source.container()) ? MediaType.parseMediaType("video/webm") : MediaType.parseMediaType("video/mp4"))
                    : MediaType.parseMediaType("video/mp2t");
            return new Fragment(new InputStreamResource(stream), Files.size(path), type);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "playback fragment could not be served", e);
        }
    }

    private void valid(String video, String token) {
        if (!userVideos.existsByIdVideoIdAndPlaybackToken(video, token)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    public record Fragment(InputStreamResource resource, long contentLength, MediaType contentType) {}
}
