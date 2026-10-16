package ch.it4user.fintube.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plans SponsorBlock cuts for an indexed source without re-encoding.
 *
 * <p>A cut removes [start, end) from every track. The start is frame accurate:
 * the video segment containing it is truncated after the last frame that ends
 * before it (dropping trailing frames never breaks decoding). Video can only
 * resume on a keyframe, so the end is the last keyframe at or before the
 * sponsor end that every video variant shares; a few seconds of sponsor can
 * remain, content is never skipped. AAC frames are independent, so the audio
 * track is cut at exactly the same times.
 */
public final class IndexedEdits {
  private static final Logger LOG = LoggerFactory.getLogger(IndexedEdits.class);
  static final String VERSION = "indexed-sponsor-v1";
  /** Keyframes of different variants within this distance count as the same position. */
  static final double KEYFRAME_TOLERANCE = 0.06d;
  /** Content after the sponsor end that may be skipped to reach the next keyframe. */
  static final double END_SLACK = 0.5d;
  static final double MIN_CUT_SECONDS = 1.0d;

  private IndexedEdits() {}

  public record Cut(double start, double end) {
    double seconds() { return end - start; }
  }

  /** One output segment: source segments, the original-time spans kept from them and its new timeline position. */
  public record Output(List<Integer> sources, List<double[]> keep, double start, double seconds, boolean truncated) {}

  public record Plan(String id, String format, List<Cut> cuts, Map<String, List<Output>> tracks, double duration) {
    public List<Output> track(String id) { return tracks.get(id); }

    /** Seconds removed before original time {@code t}. */
    public double removedBefore(double t) {
      double removed = 0;
      for (Cut cut : cuts) if (cut.end() <= t + KEYFRAME_TOLERANCE) removed += cut.seconds();
      return removed;
    }
  }

  public record Sponsor(double start, double end) {}

  /** Returns null when no sponsor can be cut. */
  public static Plan plan(MediaSourceService.Source source, List<Sponsor> sponsors) {
    if (!source.indexed() || sponsors == null || sponsors.isEmpty()) return null;
    List<MediaSourceService.IndexedTrack> videos = source.videoTracks();
    MediaSourceService.IndexedTrack audio = source.audioTrack();
    if (videos.isEmpty() || audio == null) return null;
    double duration = Math.min(videos.getFirst().duration(), audio.duration());
    for (var video : videos) duration = Math.min(duration, video.duration());
    List<Double> keyframes = commonKeyframes(videos);
    LOG.debug("event=SPONSOR_INDEXED_KEYFRAMES format={} variants={} sharedKeyframes={} firstVariantSegments={}",
        source.format(), videos.size(), keyframes.size(), videos.getFirst().segments().size());
    List<Cut> cuts = new ArrayList<>();
    for (Sponsor sponsor : sponsors.stream().sorted(java.util.Comparator.comparingDouble(Sponsor::start)).toList()) {
      double start = Math.max(0, sponsor.start());
      if (start < 0.5) start = 0;
      double end;
      if (sponsor.end() >= duration - END_SLACK) end = duration;
      else {
        end = Double.NaN;
        for (double keyframe : keyframes) if (keyframe > start && keyframe <= sponsor.end() + END_SLACK) end = keyframe;
        if (Double.isNaN(end)) {
          LOG.info("event=SPONSOR_INDEXED_CUT_RETAINED format={} sponsor={} reason=no_shared_keyframe_inside", source.format(), span(sponsor.start(), sponsor.end()));
          continue;
        }
      }
      if (start >= duration || end - start < MIN_CUT_SECONDS) {
        LOG.info("event=SPONSOR_INDEXED_CUT_RETAINED format={} sponsor={} reason=too_short_after_keyframe_alignment cut={}",
            source.format(), span(sponsor.start(), sponsor.end()), span(start, end));
        continue;
      }
      if (start == 0 && end >= duration) {
        LOG.info("event=SPONSOR_INDEXED_CUT_RETAINED format={} sponsor={} reason=would_remove_whole_video", source.format(), span(sponsor.start(), sponsor.end()));
        continue;
      }
      LOG.info("event=SPONSOR_INDEXED_CUT_PLANNED format={} sponsor={} cut={} sponsorLeftSeconds={} contentSkippedSeconds={}",
          source.format(), span(sponsor.start(), sponsor.end()), span(start, end),
          String.format(Locale.ROOT, "%.2f", Math.max(0, sponsor.end() - end)), String.format(Locale.ROOT, "%.2f", Math.max(0, end - sponsor.end())));
      if (!cuts.isEmpty() && start <= cuts.getLast().end()) {
        Cut previous = cuts.removeLast();
        cuts.add(new Cut(previous.start(), Math.max(previous.end(), end)));
      } else cuts.add(new Cut(start, end));
    }
    if (cuts.isEmpty()) return null;
    Map<String, List<Output>> tracks = new LinkedHashMap<>();
    double removed = cuts.stream().mapToDouble(Cut::seconds).sum();
    for (var video : videos) tracks.put(video.id(), videoOutputs(video, cuts));
    tracks.put(audio.id(), audioOutputs(audio, cuts));
    String id = hash(VERSION + "|" + source.format() + "|" + cuts.stream().map(c -> c.start() + "-" + c.end()).toList());
    return new Plan(id, source.format(), List.copyOf(cuts), tracks, duration - removed);
  }

  private static String span(double start, double end) {
    return String.format(Locale.ROOT, "%.2f-%.2f", start, end);
  }

  /** Segment starts of the first video variant that every other variant also starts a segment on. */
  static List<Double> commonKeyframes(List<MediaSourceService.IndexedTrack> videos) {
    List<Double> result = new ArrayList<>();
    for (AdaptiveIndex.Segment segment : videos.getFirst().segments()) {
      double time = segment.startSeconds();
      boolean shared = true;
      for (int i = 1; i < videos.size() && shared; i++) {
        shared = false;
        for (AdaptiveIndex.Segment other : videos.get(i).segments())
          if (Math.abs(other.startSeconds() - time) <= KEYFRAME_TOLERANCE) { shared = true; break; }
      }
      if (shared) result.add(time);
    }
    return result;
  }

  private static List<Output> videoOutputs(MediaSourceService.IndexedTrack track, List<Cut> cuts) {
    List<Output> result = new ArrayList<>();
    for (int i = 0; i < track.segments().size(); i++) {
      AdaptiveIndex.Segment segment = track.segments().get(i);
      double from = segment.startSeconds(), to = from + segment.seconds();
      boolean dropped = false;
      double keepUntil = to;
      for (Cut cut : cuts) {
        if (from >= cut.start() - KEYFRAME_TOLERANCE && to <= cut.end() + KEYFRAME_TOLERANCE) { dropped = true; break; }
        if (from < cut.start() - KEYFRAME_TOLERANCE && cut.start() < to) keepUntil = Math.min(keepUntil, cut.start());
      }
      if (dropped || keepUntil - from < 0.1) continue;
      result.add(new Output(List.of(i), List.of(new double[]{from, keepUntil}), from - removed(cuts, from),
          keepUntil - from, keepUntil < to));
    }
    return List.copyOf(result);
  }

  private static List<Output> audioOutputs(MediaSourceService.IndexedTrack track, List<Cut> cuts) {
    List<Output> result = new ArrayList<>();
    for (int i = 0; i < track.segments().size(); i++) {
      AdaptiveIndex.Segment segment = track.segments().get(i);
      List<double[]> keep = new ArrayList<>();
      keep.add(new double[]{segment.startSeconds(), segment.startSeconds() + segment.seconds()});
      for (Cut cut : cuts) {
        List<double[]> next = new ArrayList<>();
        for (double[] span : keep) {
          if (cut.end() <= span[0] || cut.start() >= span[1]) { next.add(span); continue; }
          if (cut.start() > span[0]) next.add(new double[]{span[0], cut.start()});
          if (cut.end() < span[1]) next.add(new double[]{cut.end(), span[1]});
        }
        keep = next;
      }
      keep.removeIf(span -> span[1] - span[0] < 0.001);
      if (keep.isEmpty()) continue;
      double seconds = keep.stream().mapToDouble(span -> span[1] - span[0]).sum();
      boolean whole = keep.size() == 1 && keep.getFirst()[0] == segment.startSeconds()
          && keep.getFirst()[1] == segment.startSeconds() + segment.seconds();
      result.add(new Output(List.of(i), List.copyOf(keep), keep.getFirst()[0] - removed(cuts, keep.getFirst()[0]), seconds, !whole));
    }
    return List.copyOf(result);
  }

  private static double removed(List<Cut> cuts, double t) {
    double removed = 0;
    for (Cut cut : cuts) if (cut.end() <= t + KEYFRAME_TOLERANCE) removed += cut.seconds();
    return removed;
  }

  private static String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
    } catch (Exception impossible) { throw new IllegalStateException(impossible); }
  }

  /**
   * Selects the samples of one output segment. Video keeps the decode-order
   * prefix whose frames end before the cut; audio keeps frames inside the spans.
   */
  public static List<Fmp4Samples.Sample> select(List<Fmp4Samples.Sample> samples, Output output, boolean audio, long timescale) {
    List<Fmp4Samples.Sample> kept = new ArrayList<>();
    double epsilon = 0.002 * timescale;
    if (!output.truncated()) return samples;
    if (!audio) {
      long until = Math.round(output.keep().getFirst()[1] * timescale);
      for (Fmp4Samples.Sample sample : samples) {
        if (sample.pts() + sample.duration() > until + epsilon) break;
        kept.add(sample);
      }
      return kept;
    }
    for (Fmp4Samples.Sample sample : samples) {
      for (double[] span : output.keep()) {
        if (sample.dts() >= span[0] * timescale - epsilon && sample.dts() + sample.duration() <= span[1] * timescale + epsilon) {
          kept.add(sample);
          break;
        }
      }
    }
    return kept;
  }
}
