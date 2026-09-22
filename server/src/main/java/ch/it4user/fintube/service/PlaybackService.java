package ch.it4user.fintube.service;

import ch.it4user.fintube.media.BackgroundFillService;
import ch.it4user.fintube.media.FragmentManager;
import ch.it4user.fintube.media.MediaSourceService;
import ch.it4user.fintube.media.SponsorRenditionService;
import ch.it4user.fintube.integration.JellyfinSyncService;
import ch.it4user.fintube.core.SettingsService;
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
    private static final String BACKGROUND_FILL_ON_PLAYBACK = "background_fill_on_playback";
    private final UserVideoRepository userVideos;
    private final AuthService auth;
    private final MediaSourceService sources;
    private final FragmentManager fragments;
    private final BackgroundFillService filler;
    private final SettingsService settings;
    private final SponsorBlockService sponsorBlock;
    private final SponsorRenditionService renditions;
    private final JellyfinSyncService jellyfin;
    private final java.util.concurrent.ConcurrentHashMap<String, String> reportedVersions = new java.util.concurrent.ConcurrentHashMap<>();

    public PlaybackService(UserVideoRepository userVideos, AuthService auth, MediaSourceService sources, FragmentManager fragments, BackgroundFillService filler, SettingsService settings, SponsorBlockService sponsorBlock, SponsorRenditionService renditions, JellyfinSyncService jellyfin) {
        this.userVideos = userVideos;
        this.auth = auth;
        this.sources = sources;
        this.fragments = fragments;
        this.filler = filler;
        this.settings = settings;
        this.sponsorBlock = sponsorBlock;
        this.renditions = renditions;
        this.jellyfin = jellyfin;
    }

    public String manifest(String video, String token) {
        return manifest(video, token, null);
    }

    public String manifest(String video, String token, HttpServletRequest request) {
        try {
            String resolvedToken = resolveToken(video, token, request);
            valid(video, resolvedToken);
            var source = sources.source(video);
            if (backgroundFillOnPlayback()) filler.enqueue(video, "playback_manifest");
            var sponsorSegments = sponsorBlock.skipSegments(video);
            var selected = renditions.select(video, source, sponsorSegments);
            reportRuntime(video, resolvedToken, selected);
            LOG.info("event=PLAYBACK_RENDITION_SELECTED video={} rendition={} edited={} durationSeconds={}",
                    video, selected.id(), selected.plan().edited(), selected.plan().duration());
            // A master playlist is the selection point. The referenced VOD and its media never change.
            return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=" + selected.plan().bandwidth()
                    + "\n" + renditionBase(video, selected.id()) + "index.m3u8?token=" + encode(resolvedToken) + "\n";
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("event=PLAYBACK_MANIFEST_FAILED video={} reason={}", video, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "playback manifest could not be resolved", e);
        }
    }

    private void reportRuntime(String video, String token, SponsorRenditionService.Selection selection) {
        try {
            if (selection.id().equals(reportedVersions.get(token))) return;
            for (var userVideo : userVideos.findByIdVideoIdIn(java.util.List.of(video))) {
                if (token.equals(userVideo.getPlaybackToken()))
                    jellyfin.syncPlaybackRuntime(video, Path.of(userVideo.getLibraryPath()), (long) Math.ceil(selection.plan().duration()));
            }
            if (reportedVersions.size() >= 10_000) reportedVersions.clear();
            reportedVersions.put(token, selection.id());
        } catch (Exception failure) {
            LOG.warn("event=PLAYBACK_RUNTIME_SYNC_FAILED video={} reason={}", video, failure.toString());
        }
    }

    public String renditionManifest(String video, String rendition, String token) {
        valid(video, token);
        try {
            var plan = renditions.read(video, rendition);
            var durations = plan.edited() ? plan.segments().stream().map(SponsorRenditionService.Segment::seconds).toList()
                    : plan.inputs().stream().map(SponsorRenditionService.Input::seconds).toList();
            StringBuilder output = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n");
            output.append("#EXT-X-TARGETDURATION:").append((int) Math.ceil(durations.stream().mapToDouble(Double::doubleValue).max().orElseThrow()))
                    .append("\n#EXT-X-MEDIA-SEQUENCE:0\n");
            for (int i = 0; i < durations.size(); i++) {
                output.append("#EXTINF:").append(String.format(java.util.Locale.ROOT, "%.6f", durations.get(i)))
                        .append(",\n").append(renditionBase(video, rendition)).append(i).append(".ts?token=").append(encode(token)).append('\n');
            }
            return output.append("#EXT-X-ENDLIST\n").toString();
        } catch (ResponseStatusException failure) { throw failure; }
        catch (Exception failure) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "playback rendition unavailable", failure); }
    }

    public Fragment renditionFragment(String video, String rendition, int index, String token) {
        valid(video, token);
        try {
            var media = renditions.open(video, rendition, index);
            return new Fragment(new InputStreamResource(media.stream()), media.bytes(), MediaType.parseMediaType(media.type()));
        } catch (ResponseStatusException failure) { throw failure; }
        catch (Exception failure) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "playback rendition fragment unavailable", failure); }
    }

    private static String renditionBase(String video, String rendition) {
        return "/play/" + encode(video) + "/rendition/" + rendition + "/";
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
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
                LOG.warn("event=PLAYBACK_SOURCE_REFRESH_REQUIRED video={} fragment={} reason=upstream_source_expired",
                    video, resolvedFragmentId);
                source = sources.refresh(video, "playback_fragment_source_expired");
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

    private boolean backgroundFillOnPlayback() {
        String value = settings.value(BACKGROUND_FILL_ON_PLAYBACK);
        return Boolean.parseBoolean(value);
    }

    private static String fragmentId(String value) {
        for (String extension : new String[]{".ts", ".mp4", ".webm"}) {
            if (value.endsWith(extension)) return value.substring(0, value.length() - extension.length());
        }
        return value;
    }

    public record Fragment(InputStreamResource resource, long contentLength, MediaType contentType) {}
}
