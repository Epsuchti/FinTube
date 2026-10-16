package ch.it4user.fintube.media;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the segment index of a single-file adaptive YouTube stream so it can
 * be served as byte-range HLS segments without downloading the whole file.
 *
 * <ul>
 *   <li>MP4 (H.264, AV1, AAC): {@code ftyp}+{@code moov} form the init section and
 *   the {@code sidx} references describe self-contained {@code moof}+{@code mdat}
 *   fragments that already start at a stream access point.</li>
 *   <li>WebM (VP9): the EBML header up to the first cluster is the stream header and
 *   {@code Cues} give every cluster position and start time.</li>
 * </ul>
 * Neighbouring index entries are grouped to roughly {@link #TARGET_SECONDS}.
 */
public final class AdaptiveIndex {
  static final double TARGET_SECONDS = 5.0d;
  static final double MAX_SECONDS = 10.5d;

  private AdaptiveIndex() {}

  public record Segment(long start, long end, double startSeconds, double seconds) {
    public long length() { return end - start; }
  }

  /** {@code initEnd} is exclusive: MP4 end of moov, WebM first cluster position. */
  public record Index(String container, long initEnd, List<Segment> segments) {
    public double duration() {
      return segments.isEmpty() ? 0 : segments.getLast().startSeconds() + segments.getLast().seconds();
    }
  }

  /** Thrown when the supplied file prefix ends before the index does. */
  public static final class NeedMoreBytesException extends Exception {
    private final long required;
    NeedMoreBytesException(long required) {
      super("index needs the first " + required + " bytes");
      this.required = required;
    }
    public long required() { return required; }
  }

  public static Index parse(String container, byte[] head, long totalLength, double durationHint)
      throws NeedMoreBytesException {
    return "webm".equals(container) ? parseWebm(head, totalLength, durationHint) : parseMp4(head);
  }

  // ---------------------------------------------------------------- MP4

  static Index parseMp4(byte[] head) throws NeedMoreBytesException {
    long offset = 0;
    long moovEnd = -1;
    while (true) {
      if (offset + 8 > head.length) throw new NeedMoreBytesException(offset + 64 * 1024);
      long size = u32(head, (int) offset);
      String type = new String(head, (int) offset + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
      int header = 8;
      if (size == 1) {
        if (offset + 16 > head.length) throw new NeedMoreBytesException(offset + 64 * 1024);
        size = ByteBuffer.wrap(head, (int) offset + 8, 8).getLong();
        header = 16;
      }
      if (size < header) throw new IllegalArgumentException("invalid MP4 box size for " + type);
      switch (type) {
        case "moov" -> moovEnd = offset + size;
        case "sidx" -> {
          if (moovEnd < 0) throw new IllegalArgumentException("MP4 sidx precedes moov");
          if (offset + size > head.length) throw new NeedMoreBytesException(offset + size);
          return new Index("mp4", moovEnd, group(sidx(head, (int) (offset + header), offset + size)));
        }
        case "moof", "mdat" -> throw new IllegalArgumentException("MP4 has no leading sidx index");
        default -> { }
      }
      offset += size;
    }
  }

  private static List<Segment> sidx(byte[] b, int p, long sidxEnd) {
    int version = b[p] & 0xff;
    p += 4; // version + flags
    p += 4; // reference_ID
    long timescale = u32(b, p); p += 4;
    long earliest;
    long firstOffset;
    if (version == 0) {
      earliest = u32(b, p); firstOffset = u32(b, p + 4); p += 8;
    } else {
      earliest = ByteBuffer.wrap(b, p, 8).getLong(); firstOffset = ByteBuffer.wrap(b, p + 8, 8).getLong(); p += 16;
    }
    p += 2; // reserved
    int count = ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff); p += 2;
    if (timescale <= 0 || count == 0) throw new IllegalArgumentException("empty MP4 sidx");
    List<Segment> refs = new ArrayList<>(count);
    long position = sidxEnd + firstOffset;
    long time = earliest;
    for (int i = 0; i < count; i++, p += 12) {
      long typeAndSize = u32(b, p);
      if ((typeAndSize & 0x8000_0000L) != 0) throw new IllegalArgumentException("hierarchical sidx is not supported");
      long size = typeAndSize & 0x7fff_ffffL;
      long duration = u32(b, p + 4);
      refs.add(new Segment(position, position + size, time / (double) timescale, duration / (double) timescale));
      position += size;
      time += duration;
    }
    return refs;
  }

  // ---------------------------------------------------------------- WebM

  private static final long EBML = 0x1A45DFA3L, SEGMENT = 0x18538067L, INFO = 0x1549A966L,
      CUES = 0x1C53BB6BL, CLUSTER = 0x1F43B675L, CUE_POINT = 0xBBL, CUE_TIME = 0xB3L,
      CUE_TRACK_POSITIONS = 0xB7L, CUE_CLUSTER_POSITION = 0xF1L, TIMECODE_SCALE = 0x2AD7B1L,
      DURATION = 0x4489L;

  private record Element(long id, long dataStart, long size) {
    long end() { return dataStart + size; }
  }

  static Index parseWebm(byte[] head, long totalLength, double durationHint) throws NeedMoreBytesException {
    Element ebml = element(head, 0);
    if (ebml.id() != EBML) throw new IllegalArgumentException("not a WebM file");
    Element segment = element(head, ebml.end());
    if (segment.id() != SEGMENT) throw new IllegalArgumentException("WebM segment missing");
    long base = segment.dataStart();
    long segmentEnd = segment.size() < 0 ? (totalLength > 0 ? totalLength : -1) : segment.end();
    long timecodeScale = 1_000_000L;
    double durationTicks = -1;
    long firstCluster;
    List<long[]> cues = null; // [time, relative cluster position]
    long offset = base;
    while (true) {
      if (offset + 12 > head.length) throw new NeedMoreBytesException(offset + 64 * 1024);
      Element child = element(head, offset);
      if (child.id() == CLUSTER) { firstCluster = offset; break; }
      if (child.size() < 0) throw new IllegalArgumentException("unknown-size WebM element before clusters");
      if ((child.id() == INFO || child.id() == CUES) && child.end() > head.length)
        throw new NeedMoreBytesException(child.end());
      if (child.id() == INFO) {
        for (Element field : children(head, child)) {
          if (field.id() == TIMECODE_SCALE) timecodeScale = uint(head, field);
          if (field.id() == DURATION) durationTicks = floatValue(head, field);
        }
      } else if (child.id() == CUES) {
        cues = new ArrayList<>();
        for (Element point : children(head, child)) {
          if (point.id() != CUE_POINT) continue;
          long time = -1, position = -1;
          for (Element field : children(head, point)) {
            if (field.id() == CUE_TIME) time = uint(head, field);
            if (field.id() == CUE_TRACK_POSITIONS) for (Element track : children(head, field))
              if (track.id() == CUE_CLUSTER_POSITION && position < 0) position = uint(head, track);
          }
          if (time >= 0 && position >= 0) cues.add(new long[]{time, position});
        }
      }
      offset = child.end();
    }
    if (cues == null || cues.isEmpty()) throw new IllegalArgumentException("WebM has no Cues before its clusters");
    cues.sort((a, b) -> Long.compare(a[1], b[1]));
    double scale = timecodeScale / 1_000_000_000d;
    double duration = durationTicks > 0 ? durationTicks * scale : durationHint;
    if (segmentEnd < 0) throw new IllegalArgumentException("WebM length is unknown");
    List<Segment> refs = new ArrayList<>(cues.size());
    for (int i = 0; i < cues.size(); i++) {
      long start = base + cues.get(i)[1];
      long end = i + 1 < cues.size() ? base + cues.get(i + 1)[1] : segmentEnd;
      double startSeconds = cues.get(i)[0] * scale;
      double endSeconds = i + 1 < cues.size() ? cues.get(i + 1)[0] * scale : duration;
      if (end <= start) continue;
      if (endSeconds <= startSeconds) endSeconds = startSeconds + 0.001;
      refs.add(new Segment(start, end, startSeconds, endSeconds - startSeconds));
    }
    if (refs.isEmpty() || refs.getFirst().start() != firstCluster)
      throw new IllegalArgumentException("WebM Cues do not start at the first cluster");
    return new Index("webm", firstCluster, group(refs));
  }

  private static List<Element> children(byte[] b, Element parent) {
    List<Element> result = new ArrayList<>();
    long offset = parent.dataStart();
    while (offset < parent.end()) {
      Element child = element(b, offset);
      if (child.size() < 0 || child.end() > parent.end()) break;
      result.add(child);
      offset = child.end();
    }
    return result;
  }

  private static Element element(byte[] b, long offset) {
    int p = (int) offset;
    int idLength = vintLength(b[p] & 0xff);
    long id = 0;
    for (int i = 0; i < idLength; i++) id = (id << 8) | (b[p + i] & 0xff);
    p += idLength;
    int sizeLength = vintLength(b[p] & 0xff);
    long size = (b[p] & 0xff) & (0xff >> sizeLength);
    boolean unknown = size == (0xff >> sizeLength);
    for (int i = 1; i < sizeLength; i++) {
      int value = b[p + i] & 0xff;
      unknown &= value == 0xff;
      size = (size << 8) | value;
    }
    return new Element(id, p + sizeLength, unknown ? -1 : size);
  }

  private static int vintLength(int first) {
    for (int length = 1; length <= 8; length++) if ((first & (0x80 >> (length - 1))) != 0) return length;
    throw new IllegalArgumentException("invalid EBML variable-size integer");
  }

  private static long uint(byte[] b, Element e) {
    long value = 0;
    for (long i = e.dataStart(); i < e.end(); i++) value = (value << 8) | (b[(int) i] & 0xff);
    return value;
  }

  private static double floatValue(byte[] b, Element e) {
    ByteBuffer buffer = ByteBuffer.wrap(b, (int) e.dataStart(), (int) e.size());
    return e.size() == 4 ? buffer.getFloat() : buffer.getDouble();
  }

  // ---------------------------------------------------------------- common

  /** Merge short index entries into segments near the target duration. */
  static List<Segment> group(List<Segment> refs) {
    List<Segment> result = new ArrayList<>();
    Segment current = null;
    for (Segment ref : refs) {
      if (current == null) { current = ref; continue; }
      boolean contiguous = current.end() == ref.start();
      if (contiguous && current.seconds() < TARGET_SECONDS && current.seconds() + ref.seconds() <= MAX_SECONDS) {
        current = new Segment(current.start(), ref.end(), current.startSeconds(), current.seconds() + ref.seconds());
      } else {
        result.add(current);
        current = ref;
      }
    }
    if (current != null) result.add(current);
    return List.copyOf(result);
  }

  private static long u32(byte[] b, int p) {
    return ((long) (b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
  }
}
