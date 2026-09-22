package ch.it4user.fintube.media;

import ch.it4user.fintube.core.ApplicationPaths;
import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.service.SponsorBlockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Plans safe whole-fragment cuts before first playback; retimes media only when requested. */
@Service
public class SponsorRenditionService {
    private static final Logger LOG = LoggerFactory.getLogger(SponsorRenditionService.class);
    private static final String VERSION = "sponsor-streamcopy-v2";
    private final ApplicationPaths paths;
    private final SettingsService settings;
    private final FragmentManager fragments;
    private final MediaSourceService sources;
    private final SponsorStreamCopy copier;
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentHashMap<String, CompletableFuture<Selection>> planning = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Path>> remuxing = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> leases = new HashMap<>();

    public SponsorRenditionService(ApplicationPaths paths, SettingsService settings, FragmentManager fragments,
                                   MediaSourceService sources, SponsorStreamCopy copier) {
        this.paths = paths;
        this.settings = settings;
        this.fragments = fragments;
        this.sources = sources;
        this.copier = copier;
    }

    public record Input(String id, URI video, URI audio, double start, double seconds) {}
    public record Interval(double start, double end) {}
    public record Segment(String file, double seconds, int sourceIndex, double removedSeconds) {}
    public record Plan(String video, String format, boolean progressive, String container,
                       List<Input> inputs, List<Interval> kept, List<Segment> segments,
                       double duration, long bandwidth, int width, int height, double fps,
                       String codecs, boolean edited) {}
    public record Selection(String id, Plan plan) {}
    public record Media(InputStream stream, long bytes, String type) {}

    public Selection select(String video, MediaSourceService.Source source,
                            List<SponsorBlockService.Segment> sponsors) throws Exception {
        List<Input> inputs = source.fragments().stream().map(f ->
                new Input(f.id(), f.url(), f.audioUrl(), f.startSeconds(), f.seconds())).toList();
        double duration = inputs.stream().mapToDouble(Input::seconds).sum();
        Plan original = new Plan(video, source.format(), source.progressive(), source.container(), inputs,
                List.of(new Interval(0, duration)), List.of(), duration, source.bandwidth(),
                source.width(), source.height(), source.fps(), source.codecs(), false);
        List<Interval> cuts = mergedCuts(duration, sponsors);
        StringBuilder identity = new StringBuilder(VERSION).append('|').append(video).append('|').append(source.format())
                .append('|').append(source.progressive()).append('|').append(source.container());
        for (Input input : inputs) identity.append('|').append(input.id()).append(':').append(input.start()).append(':').append(input.seconds());
        String id = hash(identity.append('|').append(cuts).toString());
        acquire(id);
        try {
            if (Files.isRegularFile(directory(video, id).resolve("plan.json"))) return new Selection(id, read(video, id));
            var own = new CompletableFuture<Selection>();
            var other = planning.putIfAbsent(id, own);
            if (other != null) return await(other);
            try {
                Selection selected = plan(id, original, cuts);
                own.complete(selected);
                return selected;
            } catch (Exception failure) {
                own.completeExceptionally(failure);
                throw failure;
            } finally { planning.remove(id, own); }
        } finally { release(id); }
    }

    private Selection plan(String id, Plan original, List<Interval> cuts) throws Exception {
        Path destination = directory(original.video(), id);
        Files.createDirectories(destination.getParent());
        Path work = Files.createTempDirectory(destination.getParent(), ".work-");
        try {
            boolean[] omit = new boolean[original.inputs().size()];
            Map<Integer, Path> media = new HashMap<>();
            Map<Integer, SponsorStreamCopy.Probe> probes = new HashMap<>();
            double removed = 0;
            if (cuts.size() <= 32) for (Interval cut : cuts) {
                int from = -1, to = -1;
                for (int i = 0; i < original.inputs().size(); i++) {
                    Input input = original.inputs().get(i);
                    if (input.start() >= cut.start() && input.start() + input.seconds() <= cut.end()) {
                        if (from < 0) from = i;
                        to = i + 1;
                    }
                }
                if (from < 0 || (from == 0 && to == omit.length)) continue;
                double seconds = 0;
                for (int i = from; i < to; i++) seconds += original.inputs().get(i).seconds();
                try {
                    var first = inspect(original, from, work, media, probes);
                    // Verify a decode boundary at both ends, not merely a packet's generic K flag.
                    if (!first.independent()) throw new IOException("cut does not start at an IDR boundary");
                    int previousIndex = from - 1;
                    while (previousIndex >= 0 && omit[previousIndex]) previousIndex--;
                    var before = previousIndex < 0 ? null : inspect(original, previousIndex, work, media, probes);
                    double adjacentRemoved = 0;
                    for (int i = previousIndex + 1; i < from; i++) adjacentRemoved += original.inputs().get(i).seconds();
                    if (before != null && !SponsorStreamCopy.joins(before, first, adjacentRemoved))
                        throw new IOException("cut start does not join the preceding fragment");
                    if (to < omit.length) {
                        var after = inspect(original, to, work, media, probes);
                        if (!after.independent() || !first.configuration().equals(after.configuration())
                                || Math.abs(after.video().start() - first.video().start() - seconds) > 0.05)
                            throw new IOException("cut end is not a compatible IDR/timestamp boundary");
                        if (before != null && !SponsorStreamCopy.joins(before, after, seconds + adjacentRemoved))
                            throw new IOException("audio/video clocks cannot join safely");
                        // Exercise the exact stream-copy path before advertising this cut. No decoding.
                        Path retimed = work.resolve("boundary.ts");
                        copier.remux(work, media.get(to), retimed, removed + seconds);
                        var output = copier.probe(work, retimed);
                        if (!output.independent() || !output.configuration().equals(after.configuration())
                                || Math.abs(output.video().start() - (after.video().start() - removed - seconds)) > 0.002
                                || Math.abs(output.audio().start() - (after.audio().start() - removed - seconds)) > 0.002)
                            throw new IOException("remux did not preserve the planned audio/video clock");
                    }
                    for (int i = from; i < to; i++) omit[i] = true;
                    removed += seconds;
                    LOG.info("event=SPONSOR_STREAM_COPY_CUT video={} firstFragment={} nextFragment={} removedSeconds={}",
                            original.video(), from, to, seconds);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                } catch (Exception unsafe) {
                    LOG.warn("event=SPONSOR_STREAM_COPY_CUT_RETAINED video={} startSeconds={} endSeconds={} reason={}",
                            original.video(), cut.start(), cut.end(), unsafe.toString());
                }
            }
            List<Segment> segments = new ArrayList<>();
            List<Interval> kept = new ArrayList<>();
            double offset = 0;
            for (int i = 0; i < omit.length; i++) {
                Input input = original.inputs().get(i);
                if (omit[i]) { offset += input.seconds(); continue; }
                segments.add(new Segment("segment" + i + ".ts", input.seconds(), i, offset));
                kept.add(new Interval(input.start(), input.start() + input.seconds()));
            }
            Plan result = removed == 0 ? original : new Plan(original.video(), original.format(), original.progressive(),
                    original.container(), original.inputs(), List.copyOf(kept), List.copyOf(segments),
                    segments.stream().mapToDouble(Segment::seconds).sum(), original.bandwidth(),
                    original.width(), original.height(), original.fps(), original.codecs(), true);
            // Only metadata is published here. No full download, encode job or second playback is needed.
            clear(work);
            Files.createDirectories(work);
            json.writeValue(work.resolve("plan.json").toFile(), result);
            Files.move(work, destination, StandardCopyOption.ATOMIC_MOVE);
            LOG.info("event=SPONSOR_STREAM_COPY_PLAN_READY video={} rendition={} edited={} removedSeconds={}",
                    original.video(), id, result.edited(), removed);
            return new Selection(id, result);
        } finally { clear(work); }
    }

    private SponsorStreamCopy.Probe inspect(Plan plan, int index, Path work, Map<Integer, Path> media,
                                            Map<Integer, SponsorStreamCopy.Probe> probes) throws Exception {
        if (!probes.containsKey(index)) {
            Path file = work.resolve("source" + index + ".media");
            copySource(plan, index, file);
            media.put(index, file);
            probes.put(index, copier.probe(work, file));
        }
        return probes.get(index);
    }

    private static List<Interval> mergedCuts(double duration, List<SponsorBlockService.Segment> sponsors) {
        List<Interval> sorted = sponsors.stream().filter(s -> Double.isFinite(s.startSeconds()) && Double.isFinite(s.endSeconds()))
                .map(s -> new Interval(Math.max(0, s.startSeconds()), Math.min(duration, s.endSeconds())))
                .filter(i -> i.end() > i.start()).sorted(Comparator.comparingDouble(Interval::start)).toList();
        List<Interval> merged = new ArrayList<>();
        for (Interval interval : sorted) {
            if (merged.isEmpty() || merged.getLast().end() < interval.start()) merged.add(interval);
            else {
                Interval previous = merged.removeLast();
                merged.add(new Interval(previous.start(), Math.max(previous.end(), interval.end())));
            }
        }
        return merged;
    }

    public synchronized Plan read(String video, String id) throws Exception {
        Path file = directory(video, id).resolve("plan.json");
        if (!Files.isRegularFile(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Plan plan = json.readValue(file.toFile(), Plan.class);
        if (!video.equals(plan.video())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
        return plan;
    }

    public Media open(String video, String id, int index) throws Exception {
        acquire(id);
        try {
            Plan plan = read(video, id);
            if (index < 0 || index >= (plan.edited() ? plan.segments().size() : plan.inputs().size()))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            InputStream stream;
            long bytes = -1;
            String type;
            if (plan.edited()) {
                Path file = materialize(id, plan, plan.segments().get(index));
                bytes = Files.size(file);
                stream = Files.newInputStream(file);
                type = "video/mp2t";
            } else {
                stream = openOriginal(plan, plan.inputs().get(index));
                type = !plan.progressive() ? "video/mp2t" : "webm".equalsIgnoreCase(plan.container()) ? "video/webm" : "video/mp4";
            }
            return new Media(new FilterInputStream(stream) {
                private boolean closed;
                @Override public void close() throws IOException {
                    if (!closed) { closed = true; try { super.close(); } finally { release(id); } }
                }
            }, bytes, type);
        } catch (Exception failure) { release(id); throw failure; }
    }

    private Path materialize(String id, Plan plan, Segment segment) throws Exception {
        Path target = directory(plan.video(), id).resolve(segment.file());
        if (Files.isRegularFile(target)) return target;
        String key = id + ":" + segment.sourceIndex();
        var own = new CompletableFuture<Path>();
        var other = remuxing.putIfAbsent(key, own);
        if (other != null) return await(other);
        Path work = null;
        try {
            if (!Files.isRegularFile(target)) {
                work = Files.createTempDirectory(target.getParent(), ".work-");
                Path input = work.resolve("input.media");
                copySource(plan, segment.sourceIndex(), input);
                Path output = work.resolve("output.ts");
                copier.remux(work, input, output, segment.removedSeconds());
                Files.move(output, target, StandardCopyOption.ATOMIC_MOVE);
                LOG.debug("event=SPONSOR_STREAM_COPY_FRAGMENT video={} sourceFragment={} removedSeconds={}",
                        plan.video(), segment.sourceIndex(), segment.removedSeconds());
            }
            own.complete(target);
            return target;
        } catch (Exception failure) { own.completeExceptionally(failure); throw failure; }
        finally {
            remuxing.remove(key, own);
            if (work != null) clear(work);
        }
    }

    private void copySource(Plan plan, int index, Path target) throws Exception {
        String configured = settings.value("cache_min_free_gb");
        long minimum = Long.parseLong(configured == null ? "20" : configured) * 1024L * 1024 * 1024;
        if (Files.getFileStore(target.getParent()).getUsableSpace() < minimum)
            throw new IOException("insufficient free space for SponsorBlock stream copy");
        try (InputStream stream = openOriginal(plan, plan.inputs().get(index))) {
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private InputStream openOriginal(Plan plan, Input input) throws Exception {
        try {
            return fragments.open(plan.video(), plan.format(), input.id(), input.video(), input.audio(), input.start(), true);
        } catch (FragmentManager.ExpiredSourceException expired) {
            var refreshed = sources.refresh(plan.video(), "sponsor_rendition_source_expired");
            if (!refreshed.format().equals(plan.format()) || refreshed.fragments().size() != plan.inputs().size())
                throw new IOException("source changed; refusing to mix playback versions", expired);
            for (int i = 0; i < plan.inputs().size(); i++) {
                Input pinned = plan.inputs().get(i);
                var fresh = refreshed.fragments().get(i);
                if (!pinned.id().equals(fresh.id()) || Math.abs(pinned.seconds() - fresh.seconds()) > 0.001
                        || Math.abs(pinned.start() - fresh.startSeconds()) > 0.001)
                    throw new IOException("source timeline changed", expired);
            }
            var fresh = refreshed.fragments().stream().filter(f -> f.id().equals(input.id())).findFirst().orElseThrow();
            return fragments.open(plan.video(), plan.format(), input.id(), fresh.url(), fresh.audioUrl(), fresh.startSeconds(), true);
        }
    }

    private Path directory(String video, String id) throws Exception {
        if (!id.matches("[a-f0-9]{64}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return paths.root.resolve("renditions").resolve(hash(video)).resolve(id);
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private synchronized void acquire(String id) { leases.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet(); }
    private synchronized void release(String id) {
        AtomicInteger count = leases.get(id);
        if (count != null && count.decrementAndGet() == 0) leases.remove(id);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try { return future.get(); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    synchronized void cleanup() {
        Path root = paths.root.resolve("renditions");
        if (!Files.isDirectory(root)) return;
        try {
            String configured = settings.value("cache_retention_days");
            Instant cutoff = Instant.now().minus(Duration.ofDays(Math.max(1, Integer.parseInt(configured == null ? "30" : configured))));
            try (var videos = Files.list(root)) {
                for (Path video : videos.filter(Files::isDirectory).toList()) try (var versions = Files.list(video)) {
                    for (Path version : versions.filter(Files::isDirectory).toList()) {
                        String id = version.getFileName().toString();
                        Path marker = version.resolve("plan.json");
                        if (id.matches("[a-f0-9]{64}") && !leases.containsKey(id) && Files.isRegularFile(marker)
                                && Files.getLastModifiedTime(marker).toInstant().isBefore(cutoff)) clear(version);
                        else if (id.startsWith(".work-") && planning.isEmpty()
                                && Files.getLastModifiedTime(version).toInstant().isBefore(Instant.now().minus(Duration.ofDays(1)))) clear(version);
                    }
                }
            }
        } catch (Exception failure) { LOG.warn("event=SPONSOR_RENDITION_CLEANUP_FAILED reason={}", failure.toString()); }
    }

    private static void clear(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
