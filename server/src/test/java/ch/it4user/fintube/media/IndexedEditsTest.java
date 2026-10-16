package ch.it4user.fintube.media;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndexedEditsTest {
  @Test
  void sponsorIsCutFromItsStartToTheLastSharedKeyframe() {
    var source = source(segments(6, 5), segments(6, 5), segments(3, 10));

    var plan = IndexedEdits.plan(source, List.of(new IndexedEdits.Sponsor(7.3, 18.2)));

    assertNotNull(plan);
    assertEquals(1, plan.cuts().size());
    assertEquals(7.3, plan.cuts().getFirst().start(), 1e-9);
    assertEquals(15.0, plan.cuts().getFirst().end(), 1e-9, "video resumes on the keyframe before the sponsor end");
    assertEquals(22.3, plan.duration(), 1e-9);
    var video = plan.track("v1");
    assertEquals(List.of(5.0, 2.3, 5.0, 5.0, 5.0), video.stream().map(o -> round(o.seconds())).toList());
    assertTrue(video.get(1).truncated());
    assertEquals(7.3, video.get(2).start(), 1e-9, "the segment after the cut follows the truncated one");
    var audio = plan.track("a");
    assertEquals(3, audio.size());
    assertEquals(7.3, audio.get(0).seconds(), 1e-9, "audio is cut at the exact sponsor start");
    assertEquals(7.3, audio.get(1).start(), 1e-9);
    assertEquals(5.0, audio.get(1).seconds(), 1e-9, "audio resumes exactly where video does");
  }

  @Test
  void sponsorWithoutAKeyframeInsideIsKept() {
    var source = source(segments(6, 5), segments(6, 5), segments(3, 10));
    assertNull(IndexedEdits.plan(source, List.of(new IndexedEdits.Sponsor(6, 9.0))));
  }

  @Test
  void variantsWithDifferentKeyframesOnlyCutAtSharedOnes() {
    List<AdaptiveIndex.Segment> shifted = new ArrayList<>();
    for (int i = 0; i < 6; i++) shifted.add(new AdaptiveIndex.Segment(i * 100L, i * 100L + 100, i == 3 ? 15.5 : i * 5, 5));
    var source = source(segments(6, 5), shifted, segments(3, 10));
    var plan = IndexedEdits.plan(source, List.of(new IndexedEdits.Sponsor(7.3, 18.2)));
    assertNotNull(plan);
    assertEquals(10.0, plan.cuts().getFirst().end(), 1e-9, "15s is not a keyframe in every variant");
  }

  @Test
  void sponsorAtTheEndRemovesTheTail() {
    var source = source(segments(6, 5), segments(6, 5), segments(3, 10));
    var plan = IndexedEdits.plan(source, List.of(new IndexedEdits.Sponsor(21, 29.9)));
    assertEquals(21.0, plan.duration(), 1e-9);
    assertEquals(5, plan.track("v0").size());
  }

  @Test
  void videoKeepsTheDecodeOrderPrefixThatEndsBeforeTheCut() {
    List<Fmp4Samples.Sample> samples = new ArrayList<>();
    for (int i = 0; i < 10; i++) samples.add(new Fmp4Samples.Sample(i * 100L, 100, i == 0 ? 0 : 0x0001_0000, 0, new byte[]{(byte) i}));
    var output = new IndexedEdits.Output(List.of(0), List.of(new double[]{0, 0.5}), 0, 0.5, true);
    assertEquals(5, IndexedEdits.select(samples, output, false, 1000).size());
    var whole = new IndexedEdits.Output(List.of(0), List.of(new double[]{0, 0.5}), 0, 0.5, false);
    assertEquals(10, IndexedEdits.select(samples, whole, false, 1000).size());
    var audio = new IndexedEdits.Output(List.of(0), List.of(new double[]{0, 0.2}, new double[]{0.7, 1.0}), 0, 0.5, true);
    assertEquals(List.of(0, 1, 7, 8, 9), IndexedEdits.select(samples, audio, true, 1000).stream().map(s -> (int) s.data()[0]).toList());
  }

  @Test
  void writtenFragmentsReadBackWithTheirTiming() {
    List<Fmp4Samples.Sample> samples = List.of(
        new Fmp4Samples.Sample(0, 512, 0, 1024, new byte[]{1, 2, 3}),
        new Fmp4Samples.Sample(512, 512, 0x0001_0000, -512, new byte[]{4}));
    byte[] fragment = Fmp4Samples.write(7, 3, 90_000, samples);
    var parsed = Fmp4Samples.samples(fragment, 0, new Fmp4Samples.Track(7, 90_000, 0, 0, 0));
    assertEquals(2, parsed.size());
    assertEquals(90_000, parsed.get(0).dts());
    assertEquals(90_512, parsed.get(1).dts());
    assertEquals(-512, parsed.get(1).compositionOffset());
    assertArrayEquals(new byte[]{1, 2, 3}, parsed.get(0).data());
    assertFalse(parsed.get(1).sync());
    assertEquals(48_000, Fmp4Samples.track(init(2, 48_000)).timescale());
    assertEquals(2, Fmp4Samples.track(init(2, 48_000)).trackId());
  }

  private static double round(double value) { return Math.round(value * 1000) / 1000d; }

  private static List<AdaptiveIndex.Segment> segments(int count, double seconds) {
    List<AdaptiveIndex.Segment> result = new ArrayList<>();
    for (int i = 0; i < count; i++) result.add(new AdaptiveIndex.Segment(i * 100L, i * 100L + 100, i * seconds, seconds));
    return result;
  }

  private static MediaSourceService.Source source(List<AdaptiveIndex.Segment> h264, List<AdaptiveIndex.Segment> vp9,
                                                  List<AdaptiveIndex.Segment> aac) {
    URI url = URI.create("https://example.test/media");
    var tracks = List.of(
        new MediaSourceService.IndexedTrack("v0", "137", url, "mp4", "h264", "avc1.640028", false, 1920, 1080, 30, 1, "SDR", 10, h264),
        new MediaSourceService.IndexedTrack("v1", "313", url, "webm", "vp9", "vp09.00.50.08", false, 3840, 2160, 30, 1, "SDR", 10, vp9),
        new MediaSourceService.IndexedTrack("a", "140", url, "mp4", "aac", "mp4a.40.2", true, 0, 0, 0, 1, "SDR", 10, aac));
    return new MediaSourceService.Source("313+140~137-idxv1", "313", "140", List.of(), 30, 5, "vp9", "aac", "webm",
        null, false, 3840, 2160, 30, 1, "vp09.00.50.08,mp4a.40.2", null, "policy", tracks);
  }

  private static byte[] init(int trackId, int timescale) {
    byte[] tkhd = AdaptiveTestMedia.box("tkhd", ByteBuffer.allocate(84).putInt(0).putInt(0).putInt(0).putInt(trackId).array());
    byte[] mdhd = AdaptiveTestMedia.box("mdhd", ByteBuffer.allocate(24).putInt(0).putInt(0).putInt(0).putInt(timescale).array());
    byte[] trak = AdaptiveTestMedia.box("trak", AdaptiveTestMedia.concat(tkhd, AdaptiveTestMedia.box("mdia", mdhd)));
    return AdaptiveTestMedia.concat(AdaptiveTestMedia.box("ftyp", new byte[8]), AdaptiveTestMedia.box("moov", trak));
  }
}
