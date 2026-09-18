package ch.it4user.fintube.service;

import ch.it4user.fintube.media.BackgroundFillService;
import ch.it4user.fintube.media.FragmentManager;
import ch.it4user.fintube.media.MediaSourceService;
import ch.it4user.fintube.persistence.entities.UserVideoEntity;
import ch.it4user.fintube.persistence.repositories.UserVideoRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Application service for stable Jellyfin playback and shared fragment access. */
@Service
public class PlaybackService {
    private static final Logger LOG = LoggerFactory.getLogger(PlaybackService.class);
    private final UserVideoRepository userVideos;
    private final AuthService auth;
    private final MediaSourceService sources;
    private final FragmentManager fragments;
    private final BackgroundFillService filler;

    public PlaybackService(UserVideoRepository userVideos, AuthService auth, MediaSourceService sources, FragmentManager fragments, BackgroundFillService filler) {
        this.userVideos = userVideos;
        this.auth = auth;
        this.sources = sources;
        this.fragments = fragments;
        this.filler = filler;
    }

    public String manifest(String video, String token) {
        return manifest(video, token, null);
    }

    public String manifest(String video, String token, HttpServletRequest request) {
        try {
            String resolvedToken = resolveToken(video, token, request);
            valid(video, resolvedToken);
            var source = sources.source(video);
            filler.enqueue(video);
            StringBuilder output = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n");
            output.append("#EXT-X-TARGETDURATION:").append(source.targetDuration()).append("\n#EXT-X-MEDIA-SEQUENCE:0\n");
            for (var fragment : source.fragments()) {
                output.append("#EXTINF:").append(String.format(java.util.Locale.ROOT, "%.3f", fragment.seconds())).append(",\n/play/")
                        .append(video).append("/fragment/").append(fragment.id()).append(fragmentExtension(source)).append("?token=").append(resolvedToken).append("\n");
            }
            return output.append("#EXT-X-ENDLIST\n").toString();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("event=PLAYBACK_MANIFEST_FAILED video={} reason={}", video, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "playback manifest could not be resolved", e);
        }
    }

    public Fragment fragment(String video, String fragmentId, String token) {
        return fragment(video, fragmentId, token, null);
    }

    public Fragment fragment(String video, String fragmentId, String token, HttpServletRequest request) {
        try {
            String resolvedToken = resolveToken(video, token, request);
            valid(video, resolvedToken);
            var source = sources.source(video);
            String resolvedFragmentId = fragmentId(fragmentId);
            var sourceFragment = source.fragments().stream().filter(item -> item.id().equals(resolvedFragmentId)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            Path path;
            InputStream stream;
            try {
                path = fragments.get(video, source.format(), resolvedFragmentId, sourceFragment.url(), sourceFragment.audioUrl(), sourceFragment.startSeconds(), true);
                stream = fragments.open(video, source.format(), resolvedFragmentId, sourceFragment.url(), sourceFragment.audioUrl(), sourceFragment.startSeconds(), true);
            } catch (FragmentManager.ExpiredSourceException e) {
                source = sources.refresh(video);
                var refreshed = source.fragments().stream().filter(item -> item.id().equals(resolvedFragmentId)).findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY));
                path = fragments.get(video, source.format(), resolvedFragmentId, refreshed.url(), refreshed.audioUrl(), refreshed.startSeconds(), true);
                stream = fragments.open(video, source.format(), resolvedFragmentId, refreshed.url(), refreshed.audioUrl(), refreshed.startSeconds(), true);
            }
            MediaType type = source.progressive()
                    ? ("webm".equalsIgnoreCase(source.container()) ? MediaType.parseMediaType("video/webm") : MediaType.parseMediaType("video/mp4"))
                    : MediaType.parseMediaType("video/mp2t");
            return new Fragment(new InputStreamResource(stream), Files.size(path), type);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("event=PLAYBACK_FRAGMENT_FAILED video={} fragment={} reason={}", video, fragmentId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "playback fragment could not be served", e);
        }
    }

    private String resolveToken(String video, String token, HttpServletRequest request) {
        if (token != null && !token.isBlank()) return token;
        if (request == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        AuthService.Principal principal = auth.current(request);
        if (principal == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return userVideos.findByIdUserIdAndIdVideoId(principal.id(), video)
                .map(UserVideoEntity::getPlaybackToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void valid(String video, String token) {
        if (!userVideos.existsByIdVideoIdAndPlaybackToken(video, token)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static String fragmentExtension(MediaSourceService.Source source) {
        if (!source.progressive()) return ".ts";
        return "webm".equalsIgnoreCase(source.container()) ? ".webm" : ".mp4";
    }

    private static String fragmentId(String value) {
        for (String extension : new String[]{".ts", ".mp4", ".webm"}) {
            if (value.endsWith(extension)) return value.substring(0, value.length() - extension.length());
        }
        return value;
    }

    public record Fragment(InputStreamResource resource, long contentLength, MediaType contentType) {}
}
