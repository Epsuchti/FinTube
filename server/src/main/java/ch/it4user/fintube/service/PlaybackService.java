package ch.it4user.fintube.service;

import ch.it4user.fintube.media.BackgroundFillService;
import ch.it4user.fintube.media.FragmentManager;
import ch.it4user.fintube.media.IndexedEditStore;
import ch.it4user.fintube.media.IndexedEdits;
import ch.it4user.fintube.media.IndexedMediaService;
import ch.it4user.fintube.media.MediaSourceService;
import ch.it4user.fintube.media.SponsorRenditionService;
import ch.it4user.fintube.integration.JellyfinSyncService;
import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.api.contract.model.MediaPreferences;
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
    private final UserMediaPreferencesService mediaPreferences;
    private final IndexedMediaService indexed;
    private final IndexedEditStore edits;
    private final java.util.concurrent.ConcurrentHashMap<String, String> reportedVersions = new java.util.concurrent.ConcurrentHashMap<>();

    public PlaybackService(UserVideoRepository userVideos, AuthService auth, MediaSourceService sources, FragmentManager fragments, BackgroundFillService filler, SettingsService settings, SponsorBlockService sponsorBlock, SponsorRenditionService renditions, JellyfinSyncService jellyfin, UserMediaPreferencesService mediaPreferences) {
        this(userVideos, auth, sources, fragments, filler, settings, sponsorBlock, renditions, jellyfin, mediaPreferences, null);
    }

    public PlaybackService(UserVideoRepository userVideos, AuthService auth, MediaSourceService sources, FragmentManager fragments, BackgroundFillService filler, SettingsService settings, SponsorBlockService sponsorBlock, SponsorRenditionService renditions, JellyfinSyncService jellyfin, UserMediaPreferencesService mediaPreferences, IndexedMediaService indexed) {
        this(userVideos, auth, sources, fragments, filler, settings, sponsorBlock, renditions, jellyfin, mediaPreferences, indexed, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PlaybackService(UserVideoRepository userVideos, AuthService auth, MediaSourceService sources, FragmentManager fragments, BackgroundFillService filler, SettingsService settings, SponsorBlockService sponsorBlock, SponsorRenditionService renditions, JellyfinSyncService jellyfin, UserMediaPreferencesService mediaPreferences, IndexedMediaService indexed, IndexedEditStore edits) {
        this.indexed = indexed;
        this.edits = edits;
        this.userVideos = userVideos;
        this.auth = auth;
        this.sources = sources;
        this.fragments = fragments;
        this.filler = filler;
        this.settings = settings;
        this.sponsorBlock = sponsorBlock;
        this.renditions = renditions;
        this.jellyfin = jellyfin;
        this.mediaPreferences = mediaPreferences;
    }

    /** Compatibility constructor for focused tests and legacy embedding. */
    public PlaybackService(UserVideoRepository userVideos, AuthService auth, MediaSourceService sources, FragmentManager fragments, BackgroundFillService filler, SettingsService settings, SponsorBlockService sponsorBlock, SponsorRenditionService renditions, JellyfinSyncService jellyfin) {
        this(userVideos, auth, sources, fragments, filler, settings, sponsorBlock, renditions, jellyfin, null);
    }

    public String manifest(String video, String token) {
        return manifest(video, token, null);
    }

    public String manifest(String video, String token, HttpServletRequest request) {
        try {
            String resolvedToken = resolveToken(video, token, request);
            valid(video, resolvedToken);
            long started = System.nanoTime();
            MediaPreferences preferences = preferences(video, resolvedToken);
            LOG.info("event=PLAYBACK_MANIFEST_REQUESTED video={} client=\"{}\" remote={} quality={} videoCodecs={} audioCodecs={}",
                    video, header(request, "User-Agent"), remote(request),
                    preferences == null || preferences.getQuality() == null ? "global" : preferences.getQuality().getValue(),
                    preferences == null ? "global" : preferences.getPreferredVideoCodecs(),
                    preferences == null ? "global" : preferences.getPreferredAudioCodecs());
            var source = source(video, preferences);
            LOG.debug("event=PLAYBACK_SOURCE_RESOLVED video={} format={} codecs={} resolution={}x{} durationMs={}",
                    video, source.format(), source.codecs(), source.width(), source.height(), (System.nanoTime() - started) / 1_000_000);
            if (source.indexed()) {
                IndexedEdits.Plan plan = sponsorPlan(video, source);
                reportRuntime(video, resolvedToken, "idx:" + source.format() + (plan == null ? "" : ":" + plan.id()),
                        plan == null ? source.duration() : plan.duration());
                LOG.info("event=PLAYBACK_INDEXED_SELECTED video={} format={} height={} variants={} sponsorCuts={} removedSeconds={}",
                        video, source.format(), source.height(), source.videoTracks().size(),
                        plan == null ? 0 : plan.cuts().size(),
                        plan == null ? 0 : String.format(java.util.Locale.ROOT, "%.1f", source.duration() - plan.duration()));
                return indexedMaster(video, resolvedToken, source, plan);
            }
            if (source.fmp4()) {
                String cuts = legacyCuts(video, source);
                double removed = cuts == null ? 0 : removedSeconds(source, dropped(cuts, source.fragments().size()), source.fragments().size());
                reportRuntime(video, resolvedToken, "fmp4:" + source.format() + (cuts == null ? "" : ":" + cuts), source.duration() - removed);
                LOG.info("event=PLAYBACK_FMP4_SELECTED video={} format={} height={} sponsorCuts={} removedSeconds={}", video,
                        source.format(), source.height(), cuts == null ? "none" : cuts, String.format(java.util.Locale.ROOT, "%.1f", removed));
                return fmp4Master(video, resolvedToken, source, cuts);
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

    private static String fmp4Master(String video, String token, MediaSourceService.Source source, String cuts) {
        String base = "/play/" + encode(video) + "/fmp4/" + fmp4Key(source.format()) + (cuts == null ? "" : "." + cuts) + "/";
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

    /**
     * Multivariant playlist for independently segmented fMP4 tracks. Every video
     * variant shares one AAC rendition; players pick the best variant whose
     * CODECS they can decode (for example VP9 on Android/Chrome, H.264 on Apple).
     */
    static String indexedMaster(String video, String token, MediaSourceService.Source source) {
        return indexedMaster(video, token, source, null);
    }

    static String indexedMaster(String video, String token, MediaSourceService.Source source, IndexedEdits.Plan plan) {
        String base = "/play/" + encode(video) + "/idx/" + indexedKey(source, plan) + "/";
        var audio = source.audioTrack();
        StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
                .append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\",NAME=\"Default\",DEFAULT=YES,AUTOSELECT=YES,CHANNELS=\"2\",URI=\"")
                .append(base).append(audio.id()).append("/index.m3u8?token=").append(encode(token)).append("\"\n");
        for (var track : source.videoTracks()) {
            out.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(Math.max(1, track.bandwidth() + audio.bandwidth()));
            if (track.width() > 0 && track.height() > 0)
                out.append(",RESOLUTION=").append(track.width()).append('x').append(track.height());
            if (track.fps() > 0)
                out.append(",FRAME-RATE=").append(String.format(java.util.Locale.ROOT, "%.3f", track.fps()));
            if (!"SDR".equalsIgnoreCase(track.dynamicRange()) && track.dynamicRange() != null && !track.dynamicRange().isBlank())
                out.append(",VIDEO-RANGE=").append(track.dynamicRange().toUpperCase(java.util.Locale.ROOT).contains("HLG") ? "HLG" : "PQ");
            out.append(",CODECS=\"").append(track.codecs()).append(',').append(audio.codecs()).append('"')
                    .append(",AUDIO=\"aac\"\n")
                    .append(base).append(track.id()).append("/index.m3u8?token=").append(encode(token)).append('\n');
        }
        return out.toString();
    }

    /** Route key: the source format, plus ".{planId}" when SponsorBlock cuts apply. */
    private static String indexedKey(MediaSourceService.Source source, IndexedEdits.Plan plan) {
        return fmp4Key(source.format()) + (plan == null ? "" : "." + plan.id());
    }

    private IndexedEdits.Plan sponsorPlan(String video, MediaSourceService.Source source) {
        if (sponsorBlock == null || edits == null) return null;
        try {
            var sponsors = sponsorBlock.skipSegments(video).stream()
                    .map(segment -> new IndexedEdits.Sponsor(segment.startSeconds(), segment.endSeconds())).toList();
            if (!sponsors.isEmpty()) LOG.info("event=SPONSOR_SEGMENTS_LOADED video={} sponsors={}", video, sponsors.stream()
                    .map(s -> String.format(java.util.Locale.ROOT, "%.2f-%.2f", s.start(), s.end())).toList());
            IndexedEdits.Plan plan = IndexedEdits.plan(source, sponsors);
            if (plan == null && !sponsors.isEmpty())
                LOG.info("event=SPONSOR_INDEXED_CUT_RETAINED video={} sponsors={} reason=no_shared_keyframe_inside_segment", video, sponsors.size());
            if (plan != null) {
                edits.save(video, plan);
                LOG.info("event=SPONSOR_INDEXED_PLAN_READY video={} plan={} cuts={} originalSeconds={} editedSeconds={}", video, plan.id(),
                        plan.cuts().stream().map(c -> String.format(java.util.Locale.ROOT, "%.2f-%.2f", c.start(), c.end())).toList(),
                        source.duration(), String.format(java.util.Locale.ROOT, "%.1f", plan.duration()));
            }
            return plan;
        } catch (RuntimeException failure) {
            LOG.warn("event=SPONSOR_INDEXED_PLAN_FAILED video={} reason={}", video, failure.toString());
            return null;
        }
    }

    /** Resolves the source and, for an edited route key, the stored plan that belongs to it. */
    private record IndexedRoute(MediaSourceService.Source source, IndexedEdits.Plan plan, MediaPreferences preferences) {}

    private IndexedRoute indexedRoute(String video, String key, String token) throws Exception {
        MediaPreferences preferences = preferences(video, token);
        var source = source(video, preferences);
        int dot = key.indexOf('.');
        String formatKey = dot < 0 ? key : key.substring(0, dot);
        if (!source.indexed() || !fmp4Key(source.format()).equals(formatKey)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        IndexedEdits.Plan plan = null;
        if (dot >= 0) {
            plan = edits == null ? null : edits.find(video, key.substring(dot + 1));
            if (plan == null || !plan.format().equals(source.format())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return new IndexedRoute(source, plan, preferences);
    }

    public String indexedTrackManifest(String video, String format, String trackId, String token) {
        return indexedTrackManifest(video, format, trackId, token, "-");
    }

    public String indexedTrackManifest(String video, String format, String trackId, String token, String client) {
        valid(video, token);
        try {
            IndexedRoute route = indexedRoute(video, format, token);
            var track = route.source().track(trackId);
            if (track == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            LOG.info("event=PLAYBACK_INDEXED_TRACK_REQUESTED video={} track={} format={} codecs={} resolution={}x{} plan={} client=\"{}\"",
                    video, trackId, track.formatId(), track.codecs(), track.width(), track.height(),
                    route.plan() == null ? "none" : route.plan().id(), client);
            java.util.List<Double> durations = route.plan() == null
                    ? track.segments().stream().map(segment -> segment.seconds()).toList()
                    : route.plan().track(trackId).stream().map(IndexedEdits.Output::seconds).toList();
            String base = "/play/" + encode(video) + "/idx/" + format + "/" + trackId + "/";
            double target = 1;
            for (double seconds : durations) target = Math.max(target, seconds);
            StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n")
                    .append("#EXT-X-TARGETDURATION:").append((int) Math.ceil(target)).append("\n#EXT-X-MEDIA-SEQUENCE:0\n")
                    .append("#EXT-X-MAP:URI=\"").append(base).append("init.mp4?token=").append(encode(token)).append("\"\n");
            for (int i = 0; i < durations.size(); i++) out.append("#EXTINF:")
                    .append(String.format(java.util.Locale.ROOT, "%.6f", durations.get(i)))
                    .append(",\n").append(base).append(i).append(".m4s?token=").append(encode(token)).append('\n');
            return out.append("#EXT-X-ENDLIST\n").toString();
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "indexed playlist unavailable", e); }
    }

    /** Init section ({@code index == null}) or media segment of an indexed track. */
    public Fragment indexedSegment(String video, String format, String trackId, Integer index, String token) {
        valid(video, token);
        if (indexed == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try {
            IndexedRoute route = indexedRoute(video, format, token);
            var source = route.source();
            var plan = route.plan();
            var track = source.track(trackId);
            if (track == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            int count = plan == null ? track.segments().size() : plan.track(trackId).size();
            if (index != null && (index < 0 || index >= count)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            IndexedMediaService.Media media;
            long started = System.nanoTime();
            try {
                media = openIndexed(video, source, plan, trackId, index);
            } catch (FragmentManager.ExpiredSourceException expired) {
                var refreshed = sources.refresh(video, route.preferences(), "indexed_segment_source_expired");
                var fresh = refreshed.track(trackId);
                if (!refreshed.indexed() || !refreshed.format().equals(source.format()) || fresh == null
                        || !fresh.segments().equals(track.segments()) || fresh.initEnd() != track.initEnd())
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "indexed source changed during playback", expired);
                media = openIndexed(video, refreshed, plan, trackId, index);
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            if (elapsed > 3_000)
                LOG.warn("event=PLAYBACK_INDEXED_SEGMENT_SLOW video={} track={} segment={} bytes={} durationMs={}",
                        video, trackId, index == null ? "init" : index, media.bytes(), elapsed);
            else LOG.debug("event=PLAYBACK_INDEXED_SEGMENT_SERVED video={} track={} segment={} bytes={} durationMs={}",
                        video, trackId, index == null ? "init" : index, media.bytes(), elapsed);
            return new Fragment(new InputStreamResource(media.stream()), media.bytes(), MediaType.parseMediaType(media.contentType()));
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) {
            LOG.error("event=PLAYBACK_INDEXED_SEGMENT_FAILED video={} track={} segment={} reason={}", video, trackId,
                    index == null ? "init" : index, e.toString(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "indexed segment unavailable", e);
        }
    }

    private IndexedMediaService.Media openIndexed(String video, MediaSourceService.Source source, IndexedEdits.Plan plan,
                                                  String trackId, Integer index) throws Exception {
        if (plan == null || index == null) return indexed.open(video, source, trackId, index, true);
        return indexed.openEdited(video, source, plan, trackId, index, true);
    }

    public String fmp4TrackManifest(String video, String format, String track, String token) {
        valid(video, token);
        try {
            var source = source(video, preferences(video, token));
            if (!source.fmp4() || !fmp4Key(source.format()).equals(baseKey(format))
                    || !("video".equals(track) || "audio".equals(track)))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            boolean[] dropped = dropped(cutsOf(format), source.fragments().size());
            String base = "/play/" + encode(video) + "/fmp4/" + format + "/" + track + "/";
            StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n")
                    .append("#EXT-X-TARGETDURATION:").append(source.targetDuration()).append("\n#EXT-X-MEDIA-SEQUENCE:0\n");
            if ("video".equals(track)) out.append("#EXT-X-MAP:URI=\"").append(base)
                    .append("init.mp4?token=").append(encode(token)).append("\"\n");
            for (int i = 0; i < source.fragments().size(); i++) if (!dropped[i]) out.append("#EXTINF:")
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
            MediaPreferences preferences = preferences(video, token);
            var source = source(video, preferences);
            try {
                return openFmp4TrackFragment(video, format, track, index, source);
            } catch (FragmentManager.ExpiredSourceException expired) {
                var refreshed = sources.refresh(video, preferences, "fmp4_fragment_source_expired");
                if (!baseKey(format).equals(fmp4Key(refreshed.format())) || refreshed.fragments().size() != source.fragments().size())
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "fMP4 source changed during playback", expired);
                return openFmp4TrackFragment(video, format, track, index, refreshed);
            }
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "fMP4 fragment unavailable", e); }
    }

    private Fragment openFmp4TrackFragment(String video, String format, String track, Integer index,
                                            MediaSourceService.Source source) throws Exception {
            if (!source.fmp4() || !fmp4Key(source.format()).equals(baseKey(format))
                    || !("video".equals(track) || "audio".equals(track)))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            String cuts = cutsOf(format);
            boolean[] dropped = dropped(cuts, source.fragments().size());
            boolean init = index == null;
            if (init && !"video".equals(track)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if (!init && (index < 0 || index >= source.fragments().size() || dropped[index])) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            var part = init ? null : source.fragments().get(index);
            var uri = init ? source.videoInit() : "video".equals(track) ? part.url() : part.audioUrl();
            if (uri == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            String key = init ? "init" : track + index;
            String cacheFormat = source.format() + "-" + track;
            Path path = fragments.get(video, cacheFormat, key, uri, true);
            double removed = init ? 0 : removedSeconds(source, dropped, index);
            if (removed > 0) {
                // After a dropped sponsor the segment is moved earlier on the timeline; payloads stay untouched.
                Path original = path;
                String editedFormat = cacheFormat + "-" + cuts;
                String type = "video".equals(track) ? "video/mp4" : sniffAudioType(original);
                Path init0 = "video".equals(track) ? fragments.get(video, cacheFormat, "init", source.videoInit(), true) : null;
                Path shifted = fragments.produce(video, editedFormat, key, true, (target, owner) -> {
                    Path out = FragmentManager.temporary(target);
                    if (init0 != null) Files.write(out, IndexedMediaService.shiftFmp4(Files.readAllBytes(init0),
                            Files.readAllBytes(original), index + 1, removed));
                    else if ("video/mp2t".equals(type)) indexed.shiftTs(original, out, removed);
                    else throw new java.io.IOException("audio segment cannot be retimed: " + type);
                    return out;
                });
                InputStream stream = fragments.openProduced(video, editedFormat, key, true, (target, owner) -> shifted);
                return new Fragment(new InputStreamResource(stream), Files.size(shifted), MediaType.parseMediaType(type));
            }
            long bytes = Files.size(path);
            InputStream stream = fragments.open(video, cacheFormat, key, uri, true);
            return new Fragment(new InputStreamResource(stream), bytes, MediaType.parseMediaType(
                    "video".equals(track) ? "video/mp4" : sniffAudioType(path)));
    }

    /**
     * SponsorBlock for paired fMP4 HLS sources: whole aligned fragment pairs that
     * lie inside a sponsor are dropped. Encoded in the route key as "a-b_c-d"
     * (inclusive fragment ranges), so no server-side state is needed.
     */
    private String legacyCuts(String video, MediaSourceService.Source source) {
        if (sponsorBlock == null || indexed == null) return null;
        try {
            var sponsors = sponsorBlock.skipSegments(video);
            if (sponsors.isEmpty()) return null;
            var parts = source.fragments();
            // The audio track must be retimable (MPEG-TS); probe its first fragment, which playback needs anyway.
            Path audio = fragments.get(video, source.format() + "-audio", "audio0", parts.getFirst().audioUrl(), true);
            if (!"video/mp2t".equals(sniffAudioType(audio))) {
                LOG.info("event=SPONSOR_FMP4_CUT_RETAINED video={} reason=audio_not_mpegts", video);
                return null;
            }
            String cuts = legacyCuts(parts, sponsors);
            if (cuts == null) LOG.info("event=SPONSOR_FMP4_CUT_RETAINED video={} reason=no_whole_fragment_inside_sponsor", video);
            return cuts;
        } catch (Exception failure) {
            LOG.warn("event=SPONSOR_FMP4_PLAN_FAILED video={} reason={}", video, failure.toString());
            return null;
        }
    }

    static String legacyCuts(java.util.List<MediaSourceService.Fragment> parts, java.util.List<SponsorBlockService.Segment> sponsors) {
        boolean[] drop = new boolean[parts.size()];
        int count = 0;
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            for (var sponsor : sponsors)
                if (part.startSeconds() >= sponsor.startSeconds() - 0.05 && part.startSeconds() + part.seconds() <= sponsor.endSeconds() + 0.05) {
                    drop[i] = true; count++; break;
                }
        }
        if (count == 0 || count == parts.size()) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < drop.length; i++) {
            if (!drop[i]) continue;
            int j = i;
            while (j + 1 < drop.length && drop[j + 1]) j++;
            if (out.length() > 0) out.append('_');
            out.append(i).append('-').append(j);
            i = j;
        }
        return out.toString();
    }

    static boolean[] dropped(String cuts, int count) {
        boolean[] result = new boolean[count];
        if (cuts == null || cuts.isBlank()) return result;
        if (!cuts.matches("\\d+-\\d+(_\\d+-\\d+)*")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        for (String range : cuts.split("_")) {
            String[] bounds = range.split("-");
            int from = Integer.parseInt(bounds[0]), to = Integer.parseInt(bounds[1]);
            if (from > to || to >= count) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            for (int i = from; i <= to; i++) result[i] = true;
        }
        return result;
    }

    private static double removedSeconds(MediaSourceService.Source source, boolean[] dropped, int before) {
        double removed = 0;
        for (int i = 0; i < before && i < dropped.length; i++) if (dropped[i]) removed += source.fragments().get(i).seconds();
        return removed;
    }

    private static String baseKey(String format) {
        int dot = format.indexOf('.');
        return dot < 0 ? format : format.substring(0, dot);
    }

    private static String cutsOf(String format) {
        int dot = format.indexOf('.');
        return dot < 0 ? null : format.substring(dot + 1);
    }

    /** HLS audio segments from YouTube are usually MPEG-TS; label them by content, not by URL suffix. */
    static String sniffAudioType(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] head = in.readNBytes(8);
            if (head.length > 0 && (head[0] & 0xff) == 0x47) return "video/mp2t";
            if (head.length >= 8 && new String(head, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1).matches("ftyp|styp|moof|sidx"))
                return "audio/mp4";
        } catch (java.io.IOException ignored) { }
        return "audio/aac";
    }

    private static String header(HttpServletRequest request, String name) {
        if (request == null) return "-";
        String value = request.getHeader(name);
        return value == null ? "-" : value.replace('"', '\'');
    }

    private static String remote(HttpServletRequest request) {
        if (request == null) return "-";
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded;
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
            MediaPreferences preferences = preferences(video, resolvedToken);
            var source = source(video, preferences);
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
                source = sources.refresh(video, preferences, "playback_fragment_source_expired");
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

    private MediaPreferences preferences(String video, String token) {
        if (mediaPreferences == null) return null;
        UserVideoEntity userVideo = userVideos.findByIdVideoIdIn(java.util.List.of(video)).stream()
                .filter(value -> token.equals(value.getPlaybackToken())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return mediaPreferences.get(userVideo.getId().getUserId());
    }

    private MediaSourceService.Source source(String video, MediaPreferences preferences) throws Exception {
        return preferences == null ? sources.source(video) : sources.source(video, preferences);
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
