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
            if (source.fmp4()) {
                reportRuntime(video, resolvedToken, "fmp4:" + source.format(), source.duration());
                LOG.info("event=PLAYBACK_FMP4_SELECTED video={} format={} height={}", video, source.format(), source.height());
                return fmp4Master(video, resolvedToken, source);
            }
            if (backgroundFillOnPlayback()) filler.enqueue(video, "playback_manifest");
            var sponsorSegments = sponsorBlock.skipSegments(video);
            var selected = renditions.select(video, source, sponsorSegments);
            reportRuntime(video, resolvedToken, selected);
            LOG.info("event=PLAYBACK_RENDITION_SELECTED video={} rendition={} edited={} durationSeconds={}",
                    video, selected.id(), selected.plan().edited(), selected.plan().duration());
            // A master playlist is the selection point. The referenced VOD and its media never change.
            var plan = selected.plan();
            StringBuilder variant = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=")
                    .append(Math.max(1, plan.bandwidth()));
            if (plan.width() > 0 && plan.height() > 0)
                variant.append(",RESOLUTION=").append(plan.width()).append('x').append(plan.height());
            if (plan.fps() > 0)
                variant.append(",FRAME-RATE=").append(String.format(java.util.Locale.ROOT, "%.3f", plan.fps()));
            if (plan.codecs() != null && !plan.codecs().isBlank())
                variant.append(",CODECS=\"").append(plan.codecs()).append('"');
            return variant.append('\n').append(renditionBase(video, selected.id()))
                    .append("index.m3u8?token=").append(encode(resolvedToken)).append('\n').toString();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("event=PLAYBACK_MANIFEST_FAILED video={} reason={}", video, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "playback manifest could not be resolved", e);
        }
    }

    private static String fmp4Master(String video, String token, MediaSourceService.Source source) {
        String base = "/play/" + encode(video) + "/fmp4/" + fmp4Key(source.format()) + "/";
        StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n")
                .append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Default\",DEFAULT=YES,AUTOSELECT=YES,URI=\"")
                .append(base).append("audio/index.m3u8?token=").append(encode(token)).append("\"\n")
                .append("#EXT-X-STREAM-INF:BANDWIDTH=").append(Math.max(1, source.bandwidth()));
        if (source.width() > 0 && source.height() > 0)
            out.append(",RESOLUTION=").append(source.width()).append('x').append(source.height());
        if (source.fps() > 0)
            out.append(",FRAME-RATE=").append(String.format(java.util.Locale.ROOT, "%.3f", source.fps()));
        if (source.codecs() != null) out.append(",CODECS=\"").append(source.codecs()).append('"');
        return out.append(",AUDIO=\"audio\"\n").append(base).append("video/index.m3u8?token=")
                .append(encode(token)).append('\n').toString();
    }

    public String fmp4TrackManifest(String video, String format, String track, String token) {
        valid(video, token);
        try {
            var source = sources.source(video);
            if (!source.fmp4() || !fmp4Key(source.format()).equals(format)
                    || !("video".equals(track) || "audio".equals(track)))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            String base = "/play/" + encode(video) + "/fmp4/" + format + "/" + track + "/";
            StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n")
                    .append("#EXT-X-TARGETDURATION:").append(source.targetDuration()).append("\n#EXT-X-MEDIA-SEQUENCE:0\n");
            if ("video".equals(track)) out.append("#EXT-X-MAP:URI=\"").append(base)
                    .append("init.mp4?token=").append(encode(token)).append("\"\n");
            for (int i = 0; i < source.fragments().size(); i++) out.append("#EXTINF:")
                    .append(String.format(java.util.Locale.ROOT, "%.6f", source.fragments().get(i).seconds()))
                    .append(",\n").append(base).append(i).append("video".equals(track) ? ".m4s" : ".aac")
                    .append("?token=").append(encode(token)).append('\n');
            return out.append("#EXT-X-ENDLIST\n").toString();
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "fMP4 playlist unavailable", e); }
    }

    public Fragment fmp4TrackFragment(String video, String format, String track, Integer index, String token) {
        valid(video, token);
        try {
            var source = sources.source(video);
            try {
                return openFmp4TrackFragment(video, format, track, index, source);
            } catch (FragmentManager.ExpiredSourceException expired) {
                var refreshed = sources.refresh(video, "fmp4_fragment_source_expired");
                if (!format.equals(fmp4Key(refreshed.format())) || refreshed.fragments().size() != source.fragments().size())
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "fMP4 source changed during playback", expired);
                return openFmp4TrackFragment(video, format, track, index, refreshed);
            }
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "fMP4 fragment unavailable", e); }
    }

    private Fragment openFmp4TrackFragment(String video, String format, String track, Integer index,
                                            MediaSourceService.Source source) throws Exception {
            if (!source.fmp4() || !fmp4Key(source.format()).equals(format)
                    || !("video".equals(track) || "audio".equals(track)))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            boolean init = index == null;
            if (init && !"video".equals(track)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if (!init && (index < 0 || index >= source.fragments().size())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            var part = init ? null : source.fragments().get(index);
            var uri = init ? source.videoInit() : "video".equals(track) ? part.url() : part.audioUrl();
            if (uri == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            String key = init ? "init" : track + index;
            String cacheFormat = source.format() + "-" + track;
            Path path = fragments.get(video, cacheFormat, key, uri, true);
            long bytes = Files.size(path);
            InputStream stream = fragments.open(video, cacheFormat, key, uri, true);
            return new Fragment(new InputStreamResource(stream), bytes, MediaType.parseMediaType(
                    "video".equals(track) ? "video/mp4" : "audio/aac"));
    }

    private static String fmp4Key(String format) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(format.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void reportRuntime(String video, String token, SponsorRenditionService.Selection selection) {
        reportRuntime(video, token, selection.id(), selection.plan().duration());
    }

    private void reportRuntime(String video, String token, String version, double duration) {
        try {
            if (version.equals(reportedVersions.get(token))) return;
            for (var userVideo : userVideos.findByIdVideoIdIn(java.util.List.of(video))) {
                if (token.equals(userVideo.getPlaybackToken()))
                    jellyfin.syncPlaybackRuntime(video, Path.of(userVideo.getLibraryPath()), (long) Math.ceil(duration));
            }
            if (reportedVersions.size() >= 10_000) reportedVersions.clear();
            reportedVersions.put(token, version);
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
