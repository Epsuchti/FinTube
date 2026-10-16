package ch.it4user.fintube.media;

import ch.it4user.fintube.core.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Serves indexed adaptive tracks as fMP4 HLS media.
 *
 * <p>MP4 tracks (H.264, AV1, AAC) are proxied byte ranges: the init section and
 * every segment are exactly the bytes YouTube published. WebM tracks (VP9) cannot
 * be carried in HLS, so each cluster group is stream-copied into an fMP4 fragment
 * with ffmpeg (no decoding or encoding) and its decode time is set to the cue time.
 */
@Service
public class IndexedMediaService {
  private static final Logger LOG = LoggerFactory.getLogger(IndexedMediaService.class);
  /** Timescale written into repackaged WebM video tracks. */
  static final long WEBM_VIDEO_TIMESCALE = 90_000L;
  private static final long FFMPEG_TIMEOUT_SECONDS = 60;
  private final FragmentManager fragments;
  private final SettingsService settings;
  private final Semaphore processes = new Semaphore(4, true);

  public IndexedMediaService(FragmentManager fragments, SettingsService settings) {
    this.fragments = fragments;
    this.settings = settings;
  }

  public record Media(InputStream stream, long bytes, String contentType) {}

  /** Opens the init section ({@code index == null}) or one media segment of a track. */
  public Media open(String video, MediaSourceService.Source source, String trackId, Integer index, boolean high)
      throws Exception {
    MediaSourceService.IndexedTrack track = track(source, trackId, index);
    String format = cacheFormat(source, trackId);
    String key = index == null ? "init" : "s" + index;
    Path path = path(video, source, track, format, index, high);
    long bytes = Files.size(path);
    InputStream stream = fragments.openProduced(video, format, key, high, (target, owner) -> path);
    return new Media(stream, bytes, track.audio() ? "audio/mp4" : "video/mp4");
  }

  /** Ensures the artefact is cached (used by background filling) and returns its file. */
  public Path get(String video, MediaSourceService.Source source, String trackId, Integer index, boolean high)
      throws Exception {
    MediaSourceService.IndexedTrack track = track(source, trackId, index);
    return path(video, source, track, cacheFormat(source, trackId), index, high);
  }

  private Path path(String video, MediaSourceService.Source source, MediaSourceService.IndexedTrack track,
                    String format, Integer index, boolean high) throws Exception {
    if (!"webm".equals(track.container())) {
      if (index == null) return fragments.getRange(video, format, "init", track.url(), 0, track.initEnd(), high);
      AdaptiveIndex.Segment segment = track.segments().get(index);
      return fragments.getRange(video, format, "s" + index, track.url(), segment.start(), segment.end(), high);
    }
    if (index == null) {
      return fragments.produce(video, format, "init", high, (target, owner) -> {
        Fmp4 converted = convert(video, format, track, 0, high, owner, target);
        Path out = FragmentManager.temporary(target);
        Files.write(out, converted.init());
        return out;
      });
    }
    return fragments.produce(video, format, "s" + index, high, (target, owner) -> {
      Fmp4 converted = convert(video, format, track, index, high, owner, target);
      Path out = FragmentManager.temporary(target);
      Files.write(out, converted.media());
      return out;
    });
  }

  private Fmp4 convert(String video, String format, MediaSourceService.IndexedTrack track, int index, boolean high,
                       FragmentManager.Inflight owner, Path target) throws Exception {
    Path header = fragments.getRange(video, format, "hdr", track.url(), 0, track.initEnd(), high);
    AdaptiveIndex.Segment segment = track.segments().get(index);
    Path cluster = FragmentManager.temporary(target);
    Path input = FragmentManager.temporary(target);
    Path output = FragmentManager.temporary(target);
    try {
      fragments.download(video, format, "c" + index, track.url(), segment.start(), segment.end(), cluster, high, owner, "cluster");
      try (var out = Files.newOutputStream(input)) {
        out.write(unknownSizeSegment(Files.readAllBytes(header)));
        Files.copy(cluster, out);
      }
      runFfmpeg(List.of(ffmpeg(), "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
          "-i", input.toString(), "-map", "0:v:0", "-c", "copy", "-f", "mp4",
          "-movflags", "+frag_keyframe+empty_moov+default_base_moof",
          "-video_track_timescale", Long.toString(WEBM_VIDEO_TIMESCALE),
          "-fragment_index", Integer.toString(index + 1), output.toString()));
      Fmp4 result = split(Files.readAllBytes(output));
      setDecodeTime(result.media(), Math.round(segment.startSeconds() * WEBM_VIDEO_TIMESCALE));
      LOG.debug("event=INDEXED_WEBM_SEGMENT_REPACKAGED video={} format={} segment={} bytes={}",
          video, format, index, result.media().length);
      return result;
    } finally {
      Files.deleteIfExists(cluster);
      Files.deleteIfExists(input);
      Files.deleteIfExists(output);
    }
  }

  private static MediaSourceService.IndexedTrack track(MediaSourceService.Source source, String trackId, Integer index) {
    MediaSourceService.IndexedTrack track = source.track(trackId);
    if (track == null) throw new IllegalArgumentException("unknown track " + trackId);
    if (index != null && (index < 0 || index >= track.segments().size()))
      throw new IndexOutOfBoundsException("segment " + index + " is outside track " + trackId);
    return track;
  }

  static String cacheFormat(MediaSourceService.Source source, String trackId) {
    return source.format() + "-" + trackId;
  }

  // ------------------------------------------------------------ pure helpers

  record Fmp4(byte[] init, byte[] media) {}

  /**
   * Marks the WebM Segment as unknown-sized. The repackager only receives the
   * header and one cluster group, so the original size would point past the end.
   */
  static byte[] unknownSizeSegment(byte[] header) {
    byte[] copy = header.clone();
    int p = elementEnd(copy, 0); // skip EBML header element
    if (p + 4 >= copy.length || (copy[p] & 0xff) != 0x18 || (copy[p + 1] & 0xff) != 0x53
        || (copy[p + 2] & 0xff) != 0x80 || (copy[p + 3] & 0xff) != 0x67)
      throw new IllegalArgumentException("WebM segment header missing");
    int sizeAt = p + 4;
    int length = vintLength(copy[sizeAt] & 0xff);
    copy[sizeAt] = (byte) (0xff >> (length - 1));
    for (int i = 1; i < length; i++) copy[sizeAt + i] = (byte) 0xff;
    return copy;
  }

  private static int elementEnd(byte[] b, int offset) {
    int idLength = vintLength(b[offset] & 0xff);
    int p = offset + idLength;
    int sizeLength = vintLength(b[p] & 0xff);
    long size = (b[p] & 0xff) & (0xff >> sizeLength);
    for (int i = 1; i < sizeLength; i++) size = (size << 8) | (b[p + i] & 0xff);
    return (int) (p + sizeLength + size);
  }

  private static int vintLength(int first) {
    for (int length = 1; length <= 8; length++) if ((first & (0x80 >> (length - 1))) != 0) return length;
    throw new IllegalArgumentException("invalid EBML variable-size integer");
  }

  /** Splits ffmpeg fragmented MP4 output into init (ftyp+moov) and media (moof/mdat) parts. */
  static Fmp4 split(byte[] mp4) {
    ByteArrayOutputStream init = new ByteArrayOutputStream();
    ByteArrayOutputStream media = new ByteArrayOutputStream();
    int offset = 0;
    while (offset + 8 <= mp4.length) {
      long size = u32(mp4, offset);
      String type = new String(mp4, offset + 4, 4, StandardCharsets.ISO_8859_1);
      if (size == 0) size = mp4.length - offset;
      if (size < 8 || offset + size > mp4.length) throw new IllegalArgumentException("truncated MP4 box " + type);
      switch (type) {
        case "ftyp", "moov" -> init.write(mp4, offset, (int) size);
        case "moof", "mdat" -> media.write(mp4, offset, (int) size);
        default -> { } // mfra, free, ...
      }
      offset += (int) size;
    }
    if (init.size() == 0 || media.size() == 0) throw new IllegalArgumentException("repackaged fragment is incomplete");
    return new Fmp4(init.toByteArray(), media.toByteArray());
  }

  /** Rebases every tfdt so the first fragment starts at {@code decodeTime}. */
  static void setDecodeTime(byte[] media, long decodeTime) {
    List<Integer> positions = new ArrayList<>();
    collectTfdt(media, 0, media.length, positions);
    if (positions.isEmpty()) throw new IllegalArgumentException("fragment has no tfdt");
    long delta = decodeTime - readTfdt(media, positions.getFirst());
    for (int position : positions) writeTfdt(media, position, readTfdt(media, position) + delta);
  }

  private static void collectTfdt(byte[] b, int start, int end, List<Integer> positions) {
    int offset = start;
    while (offset + 8 <= end) {
      long size = u32(b, offset);
      String type = new String(b, offset + 4, 4, StandardCharsets.ISO_8859_1);
      if (size < 8 || offset + size > end) return;
      if (type.equals("moof") || type.equals("traf")) collectTfdt(b, offset + 8, (int) (offset + size), positions);
      else if (type.equals("tfdt")) positions.add(offset);
      offset += (int) size;
    }
  }

  private static long readTfdt(byte[] b, int box) {
    int version = b[box + 8] & 0xff;
    return version == 1 ? ByteBuffer.wrap(b, box + 12, 8).getLong() : u32(b, box + 12);
  }

  private static void writeTfdt(byte[] b, int box, long value) {
    if (value < 0) throw new IllegalArgumentException("negative decode time");
    int version = b[box + 8] & 0xff;
    if (version == 1) ByteBuffer.wrap(b, box + 12, 8).putLong(value);
    else {
      if (value > 0xffff_ffffL) throw new IllegalArgumentException("decode time exceeds tfdt v0");
      ByteBuffer.wrap(b, box + 12, 4).putInt((int) value);
    }
  }

  private static long u32(byte[] b, int p) {
    return ((long) (b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
  }

  // ------------------------------------------------------------ process

  private String ffmpeg() {
    String value = settings == null ? null : settings.value("ffmpeg_path");
    return value == null || value.isBlank() ? "ffmpeg" : value;
  }

  private void runFfmpeg(List<String> command) throws Exception {
    processes.acquire();
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      CompletableFuture<byte[]> log = CompletableFuture.supplyAsync(() -> {
        try (InputStream in = process.getInputStream()) { return in.readNBytes(4096); }
        catch (IOException e) { return new byte[0]; }
      });
      try {
        if (!process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          throw new IOException("WebM repackaging timed out");
        if (process.exitValue() != 0)
          throw new IOException("WebM repackaging failed: " + new String(log.join(), StandardCharsets.UTF_8).strip());
      } finally {
        if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
      }
    } catch (IOException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("WebM")) throw e;
      throw new IOException("ffmpeg is unavailable for WebM repackaging: " + e.getMessage(), e);
    } finally {
      processes.release();
    }
  }

  static String describe(MediaSourceService.IndexedTrack track) {
    return String.format(Locale.ROOT, "%s:%s:%dx%d", track.formatId(), track.codec(), track.width(), track.height());
  }
}
