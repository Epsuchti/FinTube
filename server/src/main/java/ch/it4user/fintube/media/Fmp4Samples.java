package ch.it4user.fintube.media;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal fragmented-MP4 sample reader/writer. It reads the samples of one
 * track from moof/mdat fragments and writes a new self-contained fragment from a
 * subset of them. Sample payloads are copied unchanged; only timing metadata is
 * rewritten, so no decoder or encoder is involved.
 */
public final class Fmp4Samples {
  /** {@code flags} use the ISO BMFF sample_flags layout; bit 16 marks a non-sync sample. */
  public record Sample(long dts, long duration, int flags, int compositionOffset, byte[] data) {
    public long pts() { return dts + compositionOffset; }
    public boolean sync() { return (flags & 0x0001_0000) == 0; }
  }

  /** Track parameters from the init section: media timescale and trex defaults. */
  public record Track(int trackId, long timescale, long defaultDuration, int defaultSize, int defaultFlags) {}

  private Fmp4Samples() {}

  public static Track track(byte[] init) {
    long timescale = 0;
    int trackId = 1;
    long[] trex = {0, 0, 0};
    int[] moov = find(init, 0, init.length, "moov");
    if (moov == null) throw new IllegalArgumentException("init section has no moov");
    int[] trak = find(init, moov[0] + 8, moov[1], "trak");
    if (trak != null) {
      int[] tkhd = find(init, trak[0] + 8, trak[1], "tkhd");
      if (tkhd != null) {
        int version = init[tkhd[0] + 8] & 0xff;
        trackId = (int) u32(init, tkhd[0] + 12 + (version == 1 ? 16 : 8));
      }
      int[] mdia = find(init, trak[0] + 8, trak[1], "mdia");
      int[] mdhd = mdia == null ? null : find(init, mdia[0] + 8, mdia[1], "mdhd");
      if (mdhd != null) {
        int version = init[mdhd[0] + 8] & 0xff;
        timescale = u32(init, mdhd[0] + 12 + (version == 1 ? 16 : 8));
      }
    }
    int[] mvex = find(init, moov[0] + 8, moov[1], "mvex");
    int[] trexBox = mvex == null ? null : find(init, mvex[0] + 8, mvex[1], "trex");
    if (trexBox != null) {
      trex[0] = u32(init, trexBox[0] + 20);
      trex[1] = u32(init, trexBox[0] + 24);
      trex[2] = u32(init, trexBox[0] + 28);
    }
    if (timescale <= 0) throw new IllegalArgumentException("init section has no media timescale");
    return new Track(trackId, timescale, trex[0], (int) trex[1], (int) trex[2]);
  }

  /**
   * Reads all samples of {@code media} (one or more moof/mdat pairs).
   * {@code absoluteOffset} is the file position of media[0], used when a tfhd
   * carries an explicit base_data_offset.
   */
  public static List<Sample> samples(byte[] media, long absoluteOffset, Track track) {
    List<Sample> result = new ArrayList<>();
    int offset = 0;
    while (offset + 8 <= media.length) {
      int size = (int) u32(media, offset);
      String type = type(media, offset);
      if (size < 8 || offset + size > media.length) throw new IllegalArgumentException("truncated MP4 box " + type);
      if (type.equals("moof")) readMoof(media, offset, size, absoluteOffset, track, result);
      offset += size;
    }
    return result;
  }

  private static void readMoof(byte[] b, int moof, int moofSize, long absoluteOffset, Track track, List<Sample> out) {
    int position = moof + 8;
    long nextDataStart = -1;
    while (position < moof + moofSize) {
      int size = (int) u32(b, position);
      if (type(b, position).equals("traf")) nextDataStart = readTraf(b, moof, position, size, absoluteOffset, track, out, nextDataStart);
      position += size;
    }
  }

  private static long readTraf(byte[] b, int moof, int traf, int trafSize, long absoluteOffset, Track track,
                               List<Sample> out, long previousDataEnd) {
    long base = previousDataEnd >= 0 ? previousDataEnd : moof;
    long defaultDuration = track.defaultDuration();
    int defaultSize = track.defaultSize();
    int defaultFlags = track.defaultFlags();
    long decodeTime = -1;
    int[] tfhd = find(b, traf + 8, traf + trafSize, "tfhd");
    if (tfhd == null) throw new IllegalArgumentException("traf without tfhd");
    int tfhdFlags = (int) (u32(b, tfhd[0] + 8) & 0xffffff);
    int p = tfhd[0] + 16;
    if ((tfhdFlags & 0x01) != 0) { base = ByteBuffer.wrap(b, p, 8).getLong() - absoluteOffset; p += 8; }
    else if ((tfhdFlags & 0x02_0000) != 0) base = moof;
    if ((tfhdFlags & 0x02) != 0) p += 4;
    if ((tfhdFlags & 0x08) != 0) { defaultDuration = u32(b, p); p += 4; }
    if ((tfhdFlags & 0x10) != 0) { defaultSize = (int) u32(b, p); p += 4; }
    if ((tfhdFlags & 0x20) != 0) defaultFlags = (int) u32(b, p);
    int[] tfdt = find(b, traf + 8, traf + trafSize, "tfdt");
    if (tfdt != null) decodeTime = (b[tfdt[0] + 8] & 0xff) == 1 ? ByteBuffer.wrap(b, tfdt[0] + 12, 8).getLong() : u32(b, tfdt[0] + 12);
    if (decodeTime < 0) decodeTime = out.isEmpty() ? 0 : out.getLast().dts() + out.getLast().duration();
    long dataPosition = base;
    int position = traf + 8;
    while (position < traf + trafSize) {
      int size = (int) u32(b, position);
      if (type(b, position).equals("trun")) {
        int version = b[position + 8] & 0xff;
        int flags = (int) (u32(b, position + 8) & 0xffffff);
        int count = (int) u32(b, position + 12);
        int q = position + 16;
        if ((flags & 0x001) != 0) { dataPosition = base + ByteBuffer.wrap(b, q, 4).getInt(); q += 4; }
        int firstFlags = defaultFlags;
        boolean hasFirstFlags = (flags & 0x004) != 0;
        if (hasFirstFlags) { firstFlags = (int) u32(b, q); q += 4; }
        for (int i = 0; i < count; i++) {
          long duration = defaultDuration;
          int sampleSize = defaultSize;
          int sampleFlags = i == 0 && hasFirstFlags ? firstFlags : defaultFlags;
          int cto = 0;
          if ((flags & 0x100) != 0) { duration = u32(b, q); q += 4; }
          if ((flags & 0x200) != 0) { sampleSize = (int) u32(b, q); q += 4; }
          if ((flags & 0x400) != 0) { sampleFlags = (int) u32(b, q); q += 4; }
          if ((flags & 0x800) != 0) { cto = version == 0 ? (int) u32(b, q) : ByteBuffer.wrap(b, q, 4).getInt(); q += 4; }
          if (dataPosition < 0 || dataPosition + sampleSize > b.length)
            throw new IllegalArgumentException("sample data outside fragment");
          byte[] data = new byte[sampleSize];
          System.arraycopy(b, (int) dataPosition, data, 0, sampleSize);
          out.add(new Sample(decodeTime, duration, sampleFlags, cto, data));
          decodeTime += duration;
          dataPosition += sampleSize;
        }
      }
      position += size;
    }
    return dataPosition;
  }

  /** Writes one moof+mdat whose first sample decodes at {@code baseDecodeTime}. */
  public static byte[] write(int trackId, int sequence, long baseDecodeTime, List<Sample> samples) {
    if (samples.isEmpty()) throw new IllegalArgumentException("fragment needs at least one sample");
    int trunSize = 8 + 4 + 4 + 4 + samples.size() * 16;
    int tfhdSize = 8 + 4 + 4;
    int tfdtSize = 8 + 4 + 8;
    int trafSize = 8 + tfhdSize + tfdtSize + trunSize;
    int mfhdSize = 8 + 4 + 4;
    int moofSize = 8 + mfhdSize + trafSize;
    long dataBytes = 0;
    for (Sample sample : samples) dataBytes += sample.data().length;
    ByteBuffer out = ByteBuffer.allocate(Math.toIntExact(moofSize + 8 + dataBytes));
    out.putInt(moofSize).put(ascii("moof"));
    out.putInt(mfhdSize).put(ascii("mfhd")).putInt(0).putInt(sequence);
    out.putInt(trafSize).put(ascii("traf"));
    out.putInt(tfhdSize).put(ascii("tfhd")).putInt(0x02_0000).putInt(trackId);
    out.putInt(tfdtSize).put(ascii("tfdt")).putInt(0x0100_0000).putLong(baseDecodeTime);
    out.putInt(trunSize).put(ascii("trun")).putInt(0x0100_0F01).putInt(samples.size()).putInt(moofSize + 8);
    for (Sample sample : samples) {
      out.putInt((int) sample.duration()).putInt(sample.data().length).putInt(sample.flags()).putInt(sample.compositionOffset());
    }
    out.putInt(Math.toIntExact(8 + dataBytes)).put(ascii("mdat"));
    for (Sample sample : samples) out.put(sample.data());
    return out.array();
  }

  private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.ISO_8859_1); }

  private static int[] find(byte[] b, int start, int end, String wanted) {
    int offset = start;
    while (offset + 8 <= end) {
      int size = (int) u32(b, offset);
      if (size < 8 || offset + size > end) return null;
      if (type(b, offset).equals(wanted)) return new int[]{offset, offset + size};
      offset += size;
    }
    return null;
  }

  private static String type(byte[] b, int offset) { return new String(b, offset + 4, 4, StandardCharsets.ISO_8859_1); }

  private static long u32(byte[] b, int p) {
    return ((long) (b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
  }

  /** Concatenates byte arrays (helper for callers assembling fragments). */
  static byte[] concat(List<byte[]> parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) out.write(part, 0, part.length);
    return out.toByteArray();
  }
}
