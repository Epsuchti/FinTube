package ch.it4user.fintube.media;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveIndexTest {
  @Test
  void mp4SidxReferencesBecomeByteRangeSegments() throws Exception {
    byte[] file = AdaptiveTestMedia.mp4(4, 1000, 1000, 5000);

    AdaptiveIndex.Index index = AdaptiveIndex.parse("mp4", file, file.length, 20);

    assertEquals(AdaptiveTestMedia.mp4InitEnd(), index.initEnd(), "init must be ftyp+moov without the sidx");
    assertEquals(4, index.segments().size());
    long expectedStart = file.length - 4 * 1000L;
    assertEquals(expectedStart, index.segments().getFirst().start());
    assertEquals(file.length, index.segments().getLast().end());
    assertEquals(15d, index.segments().getLast().startSeconds(), 1e-9);
    assertEquals(20d, index.duration(), 1e-9);
  }

  @Test
  void shortMp4ReferencesAreGroupedToAboutFiveSeconds() throws Exception {
    byte[] file = AdaptiveTestMedia.mp4(10, 500, 1000, 2000);

    AdaptiveIndex.Index index = AdaptiveIndex.parse("mp4", file, file.length, 20);

    assertEquals(4, index.segments().size(), "2s references are merged into 6s, 6s, 6s and 2s segments");
    assertEquals(6d, index.segments().getFirst().seconds(), 1e-9);
    assertEquals(1500, index.segments().getFirst().length());
  }

  @Test
  void truncatedMp4HeadAsksForMoreBytes() {
    byte[] file = AdaptiveTestMedia.mp4(4, 1000, 1000, 5000);
    var more = assertThrows(AdaptiveIndex.NeedMoreBytesException.class,
        () -> AdaptiveIndex.parse("mp4", Arrays.copyOf(file, AdaptiveTestMedia.mp4InitEnd() + 10), file.length, 20));
    assertTrue(more.required() > AdaptiveTestMedia.mp4InitEnd());
  }

  @Test
  void webmCuesBecomeClusterSegments() throws Exception {
    byte[] file = AdaptiveTestMedia.webm(new long[]{0, 5000, 10000}, 2000, 13000);

    AdaptiveIndex.Index index = AdaptiveIndex.parse("webm", file, file.length, 0);

    assertEquals("webm", index.container());
    assertEquals(3, index.segments().size());
    assertEquals(index.initEnd(), index.segments().getFirst().start(), "the header ends at the first cluster");
    assertEquals(file.length, index.segments().getLast().end());
    assertEquals(10d, index.segments().getLast().startSeconds(), 1e-9);
    assertEquals(3d, index.segments().getLast().seconds(), 1e-9);
  }

  @Test
  void mp4WithoutLeadingIndexIsRejected() {
    byte[] file = AdaptiveTestMedia.concat(AdaptiveTestMedia.box("ftyp", new byte[4]),
        AdaptiveTestMedia.box("moov", new byte[8]), AdaptiveTestMedia.box("moof", new byte[8]));
    assertThrows(IllegalArgumentException.class, () -> AdaptiveIndex.parse("mp4", file, file.length, 1));
  }
}
