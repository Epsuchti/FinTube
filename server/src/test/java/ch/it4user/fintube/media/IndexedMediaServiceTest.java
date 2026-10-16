package ch.it4user.fintube.media;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class IndexedMediaServiceTest {
  @Test
  void repackagedFragmentIsSplitIntoInitAndMediaWithoutMfra() {
    byte[] ftyp = AdaptiveTestMedia.box("ftyp", new byte[8]);
    byte[] moov = AdaptiveTestMedia.box("moov", new byte[16]);
    byte[] moof = moof(0, 0);
    byte[] mdat = AdaptiveTestMedia.box("mdat", new byte[32]);
    byte[] mfra = AdaptiveTestMedia.box("mfra", new byte[8]);

    var split = IndexedMediaService.split(AdaptiveTestMedia.concat(ftyp, moov, moof, mdat, mfra));

    assertArrayEquals(AdaptiveTestMedia.concat(ftyp, moov), split.init());
    assertArrayEquals(AdaptiveTestMedia.concat(moof, mdat), split.media());
  }

  @Test
  void decodeTimesAreRebasedToTheCueTime() {
    byte[] media = AdaptiveTestMedia.concat(moof(1, 0), AdaptiveTestMedia.box("mdat", new byte[4]),
        moof(1, 450_000), AdaptiveTestMedia.box("mdat", new byte[4]));

    IndexedMediaService.setDecodeTime(media, 1_350_000);

    assertEquals(1_350_000, tfdt(media, 0));
    assertEquals(1_800_000, tfdt(media, moof(1, 0).length + 12), "later fragments keep their relative spacing");
  }

  @Test
  void webmSegmentSizeBecomesUnknownForPartialInput() {
    byte[] file = AdaptiveTestMedia.webm(new long[]{0, 5000}, 100, 10000);
    byte[] patched = IndexedMediaService.unknownSizeSegment(file);
    int segment = AdaptiveTestMedia.element(0x1A45DFA3, AdaptiveTestMedia.element(0x4282, "webm".getBytes())).length;
    assertEquals(0x01, patched[segment + 4] & 0xff);
    for (int i = 1; i < 8; i++) assertEquals(0xff, patched[segment + 4 + i] & 0xff);
    assertEquals(file.length, patched.length);
  }

  @Test
  void vp9CodecStringIsDerivedFromResolutionAndFrameRate() {
    assertEquals(50, MediaSourceService.vp9Level(3840, 2160, 30));
    assertEquals(51, MediaSourceService.vp9Level(3840, 2160, 60));
    assertEquals(41, MediaSourceService.vp9Level(1920, 1080, 60));
    assertEquals(40, MediaSourceService.vp9Level(1920, 1080, 30));
  }

  /** moof containing one traf with a version-1 tfdt. */
  private static byte[] moof(int version, long decodeTime) {
    ByteBuffer tfdt = ByteBuffer.allocate(4 + 8).put((byte) 1).put(new byte[3]).putLong(decodeTime);
    byte[] traf = AdaptiveTestMedia.box("traf", AdaptiveTestMedia.box("tfdt", tfdt.array()));
    return AdaptiveTestMedia.box("moof", traf);
  }

  private static long tfdt(byte[] media, int moofOffset) {
    // moof(8) + traf(8) + tfdt header(8) + version/flags(4)
    return ByteBuffer.wrap(media, moofOffset + 28, 8).getLong();
  }
}
