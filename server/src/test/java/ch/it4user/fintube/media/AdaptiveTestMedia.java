package ch.it4user.fintube.media;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Builds minimal YouTube-shaped adaptive files: MP4 with a leading sidx, WebM with leading Cues. */
final class AdaptiveTestMedia {
  private AdaptiveTestMedia() {}

  /** ftyp + moov + sidx followed by {@code fragments} moof/mdat pairs of {@code fragmentBytes} each. */
  static byte[] mp4(int fragments, int fragmentBytes, long timescale, long fragmentDuration) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    write(out, box("ftyp", "dash".getBytes(StandardCharsets.ISO_8859_1)));
    write(out, box("moov", new byte[32]));
    ByteBuffer sidx = ByteBuffer.allocate(4 + 4 + 4 + 4 + 4 + 2 + 2 + 12 * fragments);
    sidx.putInt(0).putInt(1).putInt((int) timescale).putInt(0).putInt(0).putShort((short) 0).putShort((short) fragments);
    for (int i = 0; i < fragments; i++) sidx.putInt(fragmentBytes).putInt((int) fragmentDuration).putInt(0x9000_0000);
    write(out, box("sidx", sidx.array()));
    for (int i = 0; i < fragments; i++) {
      write(out, box("moof", new byte[8]));
      write(out, box("mdat", new byte[fragmentBytes - 16 - 8]));
    }
    return out.toByteArray();
  }

  static int mp4InitEnd() { return box("ftyp", new byte[4]).length + box("moov", new byte[32]).length; }

  /** EBML header + Segment(Info, Cues, clusters); cue times are in milliseconds. */
  static byte[] webm(long[] cueTimesMs, int clusterBytes, double durationMs) {
    byte[] info = concat(element(0x2AD7B1, uint(1_000_000, 3)), element(0x4489, ByteBuffer.allocate(8).putDouble(durationMs).array()));
    ByteArrayOutputStream clusters = new ByteArrayOutputStream();
    for (int i = 0; i < cueTimesMs.length; i++) write(clusters, element(0x1F43B675, new byte[clusterBytes - 4 - 8]));
    // Cues positions are relative to the Segment data start; compute with a fixed-width Cues size.
    byte[] infoElement = element(0x1549A966, info);
    int cuesSize = element(0x1C53BB6B, cues(cueTimesMs, 0, clusterBytes)).length;
    long firstCluster = infoElement.length + cuesSize;
    byte[] cuesElement = element(0x1C53BB6B, cues(cueTimesMs, firstCluster, clusterBytes));
    byte[] segmentData = concat(infoElement, cuesElement, clusters.toByteArray());
    byte[] ebml = element(0x1A45DFA3, element(0x4282, "webm".getBytes(StandardCharsets.ISO_8859_1)));
    return concat(ebml, element(0x18538067, segmentData));
  }

  private static byte[] cues(long[] times, long firstCluster, int clusterBytes) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < times.length; i++) {
      byte[] positions = concat(element(0xF7, uint(1, 1)), element(0xF1, uint(firstCluster + (long) i * clusterBytes, 8)));
      write(out, element(0xBB, concat(element(0xB3, uint(times[i], 8)), element(0xB7, positions))));
    }
    return out.toByteArray();
  }

  static byte[] box(String type, byte[] payload) {
    return ByteBuffer.allocate(8 + payload.length).putInt(8 + payload.length)
        .put(type.getBytes(StandardCharsets.ISO_8859_1)).put(payload).array();
  }

  /** EBML element with an 8-byte size field, as YouTube writes them. */
  static byte[] element(long id, byte[] payload) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int idLength = id > 0xFFFFFF ? 4 : id > 0xFFFF ? 3 : id > 0xFF ? 2 : 1;
    for (int i = idLength - 1; i >= 0; i--) out.write((int) (id >>> (8 * i)) & 0xff);
    out.write(0x01);
    for (int i = 6; i >= 0; i--) out.write((int) ((long) payload.length >>> (8 * i)) & 0xff);
    write(out, payload);
    return out.toByteArray();
  }

  private static byte[] uint(long value, int length) {
    byte[] result = new byte[length];
    for (int i = 0; i < length; i++) result[length - 1 - i] = (byte) (value >>> (8 * i));
    return result;
  }

  static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) write(out, part);
    return out.toByteArray();
  }

  private static void write(ByteArrayOutputStream out, byte[] bytes) { out.write(bytes, 0, bytes.length); }
}
