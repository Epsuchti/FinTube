package ch.it4user.fintube.media;

import ch.it4user.fintube.core.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Packet inspection and timestamp-only remuxing. No video/audio decoder or encoder is used. */
@Component
public class SponsorStreamCopy {
    private final SettingsService settings;
    private final ObjectMapper json = new ObjectMapper();
    private final java.util.concurrent.Semaphore processes = new java.util.concurrent.Semaphore(2, true);

    public SponsorStreamCopy(SettingsService settings) { this.settings = settings; }

    record Track(double start, double end, double firstDts, double lastDts) {}
    record Probe(Track video, Track audio, String configuration, boolean independent) {}

    Probe probe(Path work, Path input) throws Exception {
        run(work, List.of(ffprobe(), "-v", "error", "-show_packets", "-show_streams",
                "-show_entries", "stream=index,codec_name,codec_type,width,height,profile,level,sample_rate,channels:packet=stream_index,pts_time,dts_time,duration_time,flags",
                "-of", "json", input.toAbsolutePath().toString()));
        JsonNode data = json.readTree(work.resolve("process.log").toFile());
        int video = -1, audio = -1;
        String videoConfiguration = "", audioConfiguration = "";
        for (JsonNode stream : data.path("streams")) {
            if (stream.path("codec_type").asText().equals("video") && stream.path("codec_name").asText().equals("h264")) {
                video = stream.path("index").asInt();
                videoConfiguration = "h264:" + stream.path("width").asInt() + ":" + stream.path("height").asInt()
                        + ":" + stream.path("profile").asText() + ":" + stream.path("level").asInt();
            } else if (stream.path("codec_type").asText().equals("audio") && stream.path("codec_name").asText().equals("aac")) {
                audio = stream.path("index").asInt();
                audioConfiguration = "aac:" + stream.path("sample_rate").asText() + ":" + stream.path("channels").asInt()
                        + ":" + stream.path("profile").asText();
            } else throw new IOException("SponsorBlock stream copy currently requires H.264/AAC");
        }
        if (video < 0 || audio < 0) throw new IOException("missing H.264/AAC tracks");
        Track videoTrack = track(data, video), audioTrack = track(data, audio);
        if (Math.abs(videoTrack.start() - audioTrack.start()) > 0.1
                || Math.abs(videoTrack.end() - audioTrack.end()) > 0.1)
            throw new IOException("source audio/video boundary is not aligned");

        // Extract just the first compressed access unit, not a decoded frame. A generic K flag
        // can describe an open GOP; require an actual H.264 IDR with SPS/PPS at the restart.
        Path accessUnit = work.resolve("first.h264");
        run(work, List.of(ffmpeg(), "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
                "-i", input.toAbsolutePath().toString(), "-map", "0:v:0", "-c:v", "copy",
                "-copyinkf", "-frames:v", "1", "-bsf:v", "h264_mp4toannexb", "-f", "h264", accessUnit.toString()));
        if (Files.size(accessUnit) > 16 * 1024 * 1024) throw new IOException("unexpectedly large access unit");
        byte[] bytes = Files.readAllBytes(accessUnit);
        boolean sps = false, pps = false, idr = false;
        StringBuilder headers = new StringBuilder();
        for (int position = 0; position + 4 < bytes.length; position++) {
            int prefix = prefix(bytes, position);
            if (prefix == 0) continue;
            int begin = position + prefix;
            int end = begin + 1;
            while (end + 3 < bytes.length && prefix(bytes, end) == 0) end++;
            if (end + 3 >= bytes.length) end = bytes.length;
            int type = bytes[begin] & 31;
            if (type == 7 || type == 8) {
                headers.append(HexFormat.of().formatHex(Arrays.copyOfRange(bytes, begin, end)));
                if (type == 7) sps = true; else pps = true;
            }
            if (type >= 1 && type <= 5) { idr = type == 5 && sps && pps; break; }
            position = end - 1;
        }
        boolean firstKey = false;
        for (JsonNode packet : data.path("packets")) {
            if (packet.path("stream_index").asInt() == video) {
                firstKey = packet.path("flags").asText().contains("K");
                break;
            }
        }
        return new Probe(videoTrack, audioTrack, videoConfiguration + "|" + audioConfiguration + "|" + headers, idr && firstKey);
    }

    private static int prefix(byte[] bytes, int offset) {
        if (offset + 3 >= bytes.length || bytes[offset] != 0 || bytes[offset + 1] != 0) return 0;
        if (bytes[offset + 2] == 1) return 3;
        return bytes[offset + 2] == 0 && bytes[offset + 3] == 1 ? 4 : 0;
    }

    private static Track track(JsonNode data, int stream) throws IOException {
        double start = Double.POSITIVE_INFINITY, end = Double.NEGATIVE_INFINITY;
        double firstDts = Double.NaN, lastDts = Double.NaN;
        for (JsonNode packet : data.path("packets")) {
            if (packet.path("stream_index").asInt() != stream) continue;
            double pts = packet.path("pts_time").asDouble(Double.NaN);
            double dts = packet.path("dts_time").asDouble(Double.NaN);
            double duration = packet.path("duration_time").asDouble(Double.NaN);
            if (!Double.isFinite(pts) || !Double.isFinite(dts) || !Double.isFinite(duration) || duration <= 0)
                throw new IOException("missing packet timestamps");
            if (Double.isFinite(lastDts) && (dts <= lastDts || dts - lastDts > 0.25))
                throw new IOException("noncontinuous source timestamps");
            if (!Double.isFinite(firstDts)) firstDts = dts;
            lastDts = dts;
            start = Math.min(start, pts);
            end = Math.max(end, pts + duration);
        }
        if (!Double.isFinite(firstDts)) throw new IOException("empty track");
        return new Track(start, end, firstDts, lastDts);
    }

    static boolean joins(Probe before, Probe after, double removed) {
        return after.independent() && before.configuration().equals(after.configuration())
                && joins(before.video(), after.video(), removed) && joins(before.audio(), after.audio(), removed);
    }

    private static boolean joins(Track before, Track after, double removed) {
        double gap = after.start() - removed - before.end();
        double decodeGap = after.firstDts() - removed - before.lastDts();
        return Math.abs(gap) <= 0.05 && decodeGap > 0 && decodeGap <= 0.15;
    }

    void remux(Path work, Path input, Path output, double removed) throws Exception {
        run(work, List.of(ffmpeg(), "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
                "-copyts", "-itsoffset", String.format(Locale.ROOT, "%.6f", -removed),
                "-i", input.toAbsolutePath().toString(), "-map", "0:v:0", "-map", "0:a:0",
                "-c", "copy", "-avoid_negative_ts", "disabled", "-muxpreload", "0", "-muxdelay", "0",
                "-mpegts_copyts", "1", "-mpegts_flags", "+resend_headers+initial_discontinuity",
                "-f", "mpegts", output.toAbsolutePath().toString()));
        if (!Files.isRegularFile(output) || Files.size(output) == 0) throw new IOException("empty stream-copy output");
    }

    private String ffmpeg() {
        String value = settings.value("ffmpeg_path");
        return value == null || value.isBlank() ? "ffmpeg" : value;
    }

    private String ffprobe() { return Path.of(ffmpeg()).resolveSibling("ffprobe").toString(); }

    private void run(Path work, List<String> command) throws Exception {
        processes.acquire();
        try { runProcess(work, command); }
        finally { processes.release(); }
    }

    private static void runProcess(Path work, List<String> command) throws Exception {
        Path log = work.resolve("process.log");
        Process process = new ProcessBuilder(command).directory(work.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS))
                throw new IOException("SponsorBlock media inspection/remux timed out");
            if (process.exitValue() != 0) {
                String detail;
                try (var input = Files.newInputStream(log)) { detail = new String(input.readNBytes(2048), StandardCharsets.UTF_8); }
                throw new IOException("SponsorBlock stream-copy process failed: " + detail);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
