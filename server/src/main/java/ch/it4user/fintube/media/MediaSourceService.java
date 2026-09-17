package ch.it4user.fintube.media;

import ch.it4user.fintube.core.Database;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves one deterministic representation for a video and persists the result.
 * A progressive representation is preferred; otherwise the best compatible
 * DASH video and audio tracks are paired for stream-copy remuxing.
 */
@Service
public class MediaSourceService {
  private static final Pattern EXPIRE = Pattern.compile("(?:^|[?&])expire=(\\d+)");
  private final Database db;
  private final ObjectMapper json = new ObjectMapper();
  private final ConcurrentHashMap<String, Source> sources = new ConcurrentHashMap<>();

  public MediaSourceService(Database db) { this.db = db; }

  /** A logical media fragment. audioUrl is null for progressive sources. */
  public static final class Fragment {
    private final String id;
    private final URI url;
    private final URI audioUrl;
    private final double seconds;

    public Fragment(String id, URI url, double seconds) { this(id, url, null, seconds); }
    public Fragment(String id, URI url, URI audioUrl, double seconds) {
      this.id = id;
      this.url = url;
      this.audioUrl = audioUrl;
      this.seconds = seconds > 0 ? seconds : 4d;
    }
    public String id() { return id; }
    public URI url() { return url; }
    public URI audioUrl() { return audioUrl; }
    public double seconds() { return seconds; }
    public boolean hasSeparateAudio() { return audioUrl != null; }
  }

  /** Persisted source selection and fragment timeline. */
  public static final class Source {
    private final String format;
    private final String videoFormat;
    private final String audioFormat;
    private final List<Fragment> fragments;
    private final int duration;
    private final int targetDuration;
    private final String videoCodec;
    private final String audioCodec;
    private final String container;
    private final Instant expiresAt;
    private final boolean progressive;

    /** Kept for callers compiled against the original vertical-slice API. */
    public Source(String format, List<Fragment> fragments, int duration) {
      this(format, format, null, fragments, duration, maxTarget(fragments), null, null, null, null, true);
    }

    Source(String format, String videoFormat, String audioFormat, List<Fragment> fragments,
           int duration, int targetDuration, String videoCodec, String audioCodec,
           String container, Instant expiresAt, boolean progressive) {
      this.format = format;
      this.videoFormat = videoFormat;
      this.audioFormat = audioFormat;
      this.fragments = List.copyOf(fragments);
      this.duration = Math.max(duration, 0);
      this.targetDuration = Math.max(1, targetDuration);
      this.videoCodec = videoCodec;
      this.audioCodec = audioCodec;
      this.container = container;
      this.expiresAt = expiresAt;
      this.progressive = progressive;
    }

    public String format() { return format; }
    public String videoFormat() { return videoFormat; }
    public String audioFormat() { return audioFormat; }
    public List<Fragment> fragments() { return fragments; }
    public int duration() { return duration; }
    public int targetDuration() { return targetDuration; }
    public String videoCodec() { return videoCodec; }
    public String audioCodec() { return audioCodec; }
    public String container() { return container; }
    public Instant expiresAt() { return expiresAt; }
    public boolean progressive() { return progressive; }
    public boolean expired() { return expiresAt != null && Instant.now().plusSeconds(60).isAfter(expiresAt); }

    private static int maxTarget(List<Fragment> fragments) {
      double max = 4;
      for (Fragment f : fragments) max = Math.max(max, f.seconds());
      return (int) Math.ceil(max);
    }
  }

  private record Candidate(JsonNode node, int height, String videoCodec, String audioCodec,
                           String ext, int codecRank, double bitrate) {
    String id() { return node.path("format_id").asText(); }
    boolean hasVideo() { return !"none".equalsIgnoreCase(videoCodec) && !videoCodec.isBlank(); }
    boolean hasAudio() { return !"none".equalsIgnoreCase(audioCodec) && !audioCodec.isBlank(); }
    boolean progressive() { return hasVideo() && hasAudio(); }
  }

  /** Load a valid persisted probe before invoking yt-dlp after a restart. */
  public Source source(String video) throws Exception {
    Source memory = sources.get(video);
    if (memory != null && !memory.expired()) return memory;
    Source persisted = load(video);
    if (persisted != null && !persisted.expired()) {
      sources.put(video, persisted);
      return persisted;
    }
    return refresh(video);
  }

  /** Force a new yt-dlp probe, replacing the persisted source URL set. */
  public synchronized Source refresh(String video) throws Exception {
    var settings = db.settings(false);
    String bin = settings.getOrDefault("yt_dlp_path", "yt-dlp");
    List<String> command = new ArrayList<>(List.of(bin, "-J", "--no-playlist"));
    String proxy = proxyArgument(settings);
    if (proxy != null && !proxy.isBlank()) command.addAll(List.of("--proxy", proxy));
    String cookieFile = settings.get("cookie_file");
    if (cookieFile != null && !cookieFile.isBlank()) command.addAll(List.of("--cookies", cookieFile));
    String playerClient = settings.get("youtube_player_client");
    String poToken = settings.get("youtube_po_token");
    String providerArgs = "true".equalsIgnoreCase(settings.getOrDefault("youtube_po_token_provider_enabled", "true")) ? settings.get("youtube_po_token_provider_args") : "";
    if (playerClient != null && !playerClient.isBlank()) command.addAll(List.of("--extractor-args", "youtube:player_client=" + playerClient));
    if (poToken != null && !poToken.isBlank()) command.addAll(List.of("--extractor-args", "youtube:po_token=" + poToken));
    // Provider plugins are discovered by yt-dlp itself. This option only forwards
    // their documented extractor arguments; it never executes a shell command.
    if (providerArgs != null && !providerArgs.isBlank()) command.addAll(List.of("--extractor-args", providerArgs));
    command.add("https://www.youtube.com/watch?v=" + URLEncoder.encode(video, StandardCharsets.UTF_8));
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    byte[] out = p.getInputStream().readAllBytes();
    if (p.waitFor() != 0 && providerArgs != null && !providerArgs.isBlank()) {
      // Provider plugins are optional outside the Compose deployment. Retry
      // without this provider only; cookies/manual tokens remain intact.
      int index = command.lastIndexOf(providerArgs);
      if (index > 0 && "--extractor-args".equals(command.get(index - 1))) { command.remove(index); command.remove(index - 1); }
      p = new ProcessBuilder(command).redirectErrorStream(true).start();
      out = p.getInputStream().readAllBytes();
    }
    if (p.waitFor() != 0) throw new IllegalStateException("yt-dlp probe failed");
    JsonNode root = json.readTree(out);
    Source selected = select(root);
    persist(video, selected);
    sources.put(video, selected);
    return selected;
  }

  /**
   * yt-dlp accepts proxy credentials only as part of the proxy URL. Build that
   * argument at the last possible moment from encrypted settings, with strict
   * percent encoding and no credential-bearing value ever written to logs.
   */
  private String proxyArgument(java.util.Map<String, String> settings) {
    String base = settings.get("proxy_url");
    if (base == null || base.isBlank()) return base;
    String username = settings.getOrDefault("proxy_username", "");
    String password = settings.getOrDefault("proxy_password", "");
    if (username.isBlank() && password.isBlank()) return base;
    try {
      URI uri = URI.create(base);
      String authority = percentEncode(username) + ":" + percentEncode(password) + "@" + uri.getRawAuthority();
      StringBuilder result = new StringBuilder(uri.getScheme()).append("://").append(authority);
      if (uri.getRawPath() != null) result.append(uri.getRawPath());
      if (uri.getRawQuery() != null) result.append('?').append(uri.getRawQuery());
      if (uri.getRawFragment() != null) result.append('#').append(uri.getRawFragment());
      return result.toString();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("invalid proxy configuration");
    }
  }

  private static String percentEncode(String value) {
    StringBuilder out = new StringBuilder();
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
          (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') out.append((char)c);
      else out.append('%').append(String.format(Locale.ROOT, "%02X", c));
    }
    return out.toString();
  }

  /** Select the best representation under the administrator's quality/codec policy. */
  public Source select(JsonNode root) {
    String quality = setting("stream_quality", "720").toLowerCase(Locale.ROOT);
    int ceiling = "best".equals(quality) || "best-compatible".equals(quality)
        ? Integer.MAX_VALUE : parseQuality(quality);
    List<String> preferredVideo = codecs("preferred_video_codecs", List.of("av1", "vp9", "h264"));
    List<String> preferredAudio = codecs("preferred_audio_codecs", List.of("opus", "aac"));
    Set<String> allowedVideo = allowed("allowed_video_codecs");
    Set<String> allowedAudio = allowed("allowed_audio_codecs");

    List<Candidate> all = new ArrayList<>();
    for (JsonNode f : root.path("formats")) {
      String vc = codecName(f.path("vcodec").asText("none"));
      String ac = codecName(f.path("acodec").asText("none"));
      if (!allowedVideo.isEmpty() && !"none".equals(vc) && !allowedVideo.contains(vc)) continue;
      if (!allowedAudio.isEmpty() && !"none".equals(ac) && !allowedAudio.contains(ac)) continue;
      int h = f.path("height").asInt(0);
      if (h > ceiling) continue;
      int rank = !"none".equals(vc) ? rank(vc, preferredVideo) : rank(ac, preferredAudio);
      all.add(new Candidate(f, h, vc, ac, f.path("ext").asText(""), rank, f.path("tbr").asDouble(0)));
    }

    Comparator<Candidate> videoOrder = Comparator.comparingInt(Candidate::height).reversed()
        .thenComparing(Comparator.comparingInt(Candidate::codecRank).reversed())
        .thenComparing(Comparator.comparingDouble(Candidate::bitrate).reversed());
    List<Candidate> progressive = all.stream().filter(Candidate::progressive)
        .filter(c -> directPlayable(c)).sorted(videoOrder).toList();
    // Fragmented progressive streams retain the complete timeline and can be
    // fetched by segment. Prefer them over a single full-file URL.
    List<Candidate> fragmentedProgressive = progressive.stream()
        .filter(this::seekable).toList();
    if (!fragmentedProgressive.isEmpty()) return makeSource(root, fragmentedProgressive.get(0), null, true);

    List<Candidate> videos = all.stream().filter(Candidate::hasVideo).filter(c -> !c.hasAudio())
        .filter(this::directPlayable).filter(this::seekable).sorted(videoOrder).toList();
    List<Candidate> audios = all.stream().filter(c -> c.hasAudio() && !c.hasVideo())
        .filter(this::directPlayable).filter(this::seekable).sorted(Comparator.comparingInt(Candidate::codecRank)
            .thenComparingDouble(Candidate::bitrate).reversed()).toList();
    if (videos.isEmpty() || audios.isEmpty()) throw new IllegalStateException("no seekable fragmented representation available");
    return makeSource(root, videos.get(0), audios.get(0), false);
  }

  private Source makeSource(JsonNode root, Candidate video, Candidate audio, boolean progressive) {
    String format = progressive ? video.id() : video.id() + "+" + audio.id();
    List<JsonNode> videoParts = parts(video.node());
    List<JsonNode> audioParts = audio == null ? List.of() : parts(audio.node());
    int count = videoParts.size();
    if (count < 2) throw new IllegalStateException("source has no segment timeline; refusing non-seekable fallback");
    List<Fragment> fragments = new ArrayList<>(count);
    double total = root.path("duration").asDouble(0);
    Instant expiry = expiry(video.node());
    if (audio != null) expiry = minExpiry(expiry, expiry(audio.node()));
    for (int i = 0; i < count; i++) {
      JsonNode vp = videoParts.isEmpty() ? video.node() : videoParts.get(i);
      URI vu = uri(vp.path("url").asText(null));
      expiry = minExpiry(expiry, expiry(vp));
      URI au = null;
      if (audio != null) {
        JsonNode ap = audioParts.isEmpty() ? audio.node() : audioParts.get(Math.min(i, audioParts.size() - 1));
        au = uri(ap.path("url").asText(null));
        expiry = minExpiry(expiry, expiry(ap));
      }
      if (vu == null) throw new IllegalStateException("selected video format has no source URL");
      double seconds = vp.path("duration").asDouble(0);
      if (seconds <= 0 && i == count - 1 && total > 0) seconds = total - fragments.stream().mapToDouble(Fragment::seconds).sum();
      fragments.add(new Fragment("v" + i, vu, au, seconds > 0 ? seconds : 4));
    }
    int target = 1;
    for (Fragment f : fragments) target = Math.max(target, (int) Math.ceil(f.seconds()));
    return new Source(format, video.id(), audio == null ? null : audio.id(), fragments,
        (int) Math.ceil(total), target, video.videoCodec(), audio == null ? video.audioCodec() : audio.audioCodec(),
        video.ext(), expiry, progressive);
  }

  private List<JsonNode> parts(JsonNode format) {
    List<JsonNode> result = new ArrayList<>();
    if (format.path("fragments").isArray()) format.path("fragments").forEach(result::add);
    return result;
  }
  private boolean seekable(Candidate candidate) { return candidate.node().path("fragments").isArray() && candidate.node().path("fragments").size() > 1; }

  private boolean directPlayable(Candidate c) {
    if (c.node().path("url").asText("").isBlank() && !c.node().path("fragments").isArray()) return false;
    String protocol = c.node().path("protocol").asText("").toLowerCase(Locale.ROOT);
    return protocol.isBlank() || protocol.startsWith("http") || protocol.contains("m3u8");
  }

  private Source load(String video) throws Exception {
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement("SELECT source_json FROM media_sources WHERE video_id=?")) {
      p.setString(1, video);
      try (ResultSet r = p.executeQuery()) { return r.next() ? decode(r.getString(1)) : null; }
    }
  }

  private void persist(String video, Source source) throws Exception {
    String text = json.writeValueAsString(encode(source));
    try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(
        "INSERT INTO media_sources(video_id,format_key,source_json,duration_seconds,expires_at,updated_at) VALUES(?,?,?,?,?,?) " +
            "ON CONFLICT(video_id) DO UPDATE SET format_key=excluded.format_key,source_json=excluded.source_json,duration_seconds=excluded.duration_seconds,expires_at=excluded.expires_at,updated_at=excluded.updated_at")) {
      p.setString(1, video); p.setString(2, source.format()); p.setString(3, text); p.setInt(4, source.duration());
      p.setString(5, source.expiresAt() == null ? null : source.expiresAt().toString()); p.setString(6, Database.now()); p.executeUpdate();
    }
  }

  private ObjectNode encode(Source source) {
    ObjectNode out = json.createObjectNode();
    out.put("format", source.format()); out.put("videoFormat", source.videoFormat());
    if (source.audioFormat() != null) out.put("audioFormat", source.audioFormat());
    out.put("duration", source.duration()); out.put("targetDuration", source.targetDuration());
    if (source.videoCodec() != null) out.put("videoCodec", source.videoCodec());
    if (source.audioCodec() != null) out.put("audioCodec", source.audioCodec());
    if (source.container() != null) out.put("container", source.container());
    out.put("progressive", source.progressive());
    if (source.expiresAt() != null) out.put("expiresAt", source.expiresAt().toString());
    ArrayNode fragments = out.putArray("fragments");
    for (Fragment f : source.fragments()) {
      ObjectNode x = fragments.addObject(); x.put("id", f.id()); x.put("url", f.url().toString()); x.put("seconds", f.seconds());
      if (f.audioUrl() != null) x.put("audioUrl", f.audioUrl().toString());
    }
    return out;
  }

  private Source decode(String text) throws java.io.IOException {
    JsonNode x = json.readTree(text);
    List<Fragment> fragments = new ArrayList<>();
    for (JsonNode f : x.path("fragments")) fragments.add(new Fragment(f.path("id").asText(), uriRequired(f.path("url").asText()), uri(f.path("audioUrl").asText(null)), f.path("seconds").asDouble(4)));
    Instant expiry = x.hasNonNull("expiresAt") ? Instant.parse(x.path("expiresAt").asText()) : null;
    return new Source(x.path("format").asText(), x.path("videoFormat").asText(x.path("format").asText()),
        x.path("audioFormat").isMissingNode() ? null : x.path("audioFormat").asText(), fragments,
        x.path("duration").asInt(), x.path("targetDuration").asInt(Source.maxTarget(fragments)),
        textOrNull(x, "videoCodec"), textOrNull(x, "audioCodec"), textOrNull(x, "container"), expiry,
        x.path("progressive").asBoolean(true));
  }

  private static URI uriRequired(String value) { URI result = uri(value); if (result == null) throw new IllegalArgumentException("persisted source has no URL"); return result; }
  private static URI uri(String value) { try { return value == null || value.isBlank() ? null : URI.create(value); } catch (IllegalArgumentException e) { return null; } }
  private static String textOrNull(JsonNode x, String name) { return x.hasNonNull(name) ? x.path(name).asText() : null; }
  private static Instant minExpiry(Instant a, Instant b) { if (a == null) return b; if (b == null) return a; return a.isBefore(b) ? a : b; }
  private static Instant expiry(JsonNode format) {
    String value = format.path("url").asText(""); Matcher matcher = EXPIRE.matcher(value);
    if (!matcher.find()) return null;
    try { return Instant.ofEpochSecond(Long.parseLong(matcher.group(1))); } catch (NumberFormatException ignored) { return null; }
  }
  private int parseQuality(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 720; } }
  private String setting(String key, String fallback) { try { return db.settings(false).getOrDefault(key, fallback); } catch (SQLException e) { return fallback; } }
  private List<String> codecs(String key, List<String> fallback) {
    String value = setting(key, String.join(",", fallback));
    List<String> result = Arrays.stream(value.split(",")).map(MediaSourceService::codecName).filter(s -> !s.isBlank()).toList();
    return result.isEmpty() ? fallback : result;
  }
  private Set<String> allowed(String key) {
    String value = setting(key, "");
    if (value.isBlank()) return new HashSet<>();
    return new HashSet<>(Arrays.stream(value.split(",")).map(MediaSourceService::codecName).filter(s -> !s.isBlank()).toList());
  }
  private static int rank(String codec, List<String> preferred) { int i = preferred.indexOf(codec); return i < 0 ? 0 : preferred.size() - i; }
  private static String codecName(String codec) {
    String c = codec == null ? "" : codec.toLowerCase(Locale.ROOT);
    if (c.startsWith("av01") || c.startsWith("av1")) return "av1";
    if (c.startsWith("vp09") || c.startsWith("vp9")) return "vp9";
    if (c.startsWith("avc1") || c.startsWith("h264") || c.startsWith("avc")) return "h264";
    if (c.startsWith("opus")) return "opus";
    if (c.startsWith("mp4a") || c.startsWith("aac")) return "aac";
    return c;
  }
}
