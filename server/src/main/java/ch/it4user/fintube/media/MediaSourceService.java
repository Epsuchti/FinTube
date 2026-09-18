package ch.it4user.fintube.media;

import ch.it4user.fintube.core.ApplicationClock;
import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.persistence.entities.MediaSourceEntity;
import ch.it4user.fintube.persistence.repositories.MediaSourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves one deterministic representation for a video and persists the result.
 * A progressive representation is preferred; otherwise the best compatible
 * DASH video and audio tracks are paired for stream-copy remuxing.
 */
@Service
public class MediaSourceService {
  private static final String DEFAULT_PO_TOKEN_PROVIDER_ARGS = "youtubepot-bgutilhttp:base_url=http://127.0.0.1:4416";
  private static final String REMUX_FORMAT_VERSION = "-tsv2";
  private static final Logger LOG = LoggerFactory.getLogger(MediaSourceService.class);
  private static final Pattern EXPIRE = Pattern.compile("(?:^|[?&])expire=(\\d+)");
  private final SettingsService settingsService;
  private final MediaSourceRepository mediaSourceRepository;
  private final String poTokenProviderUrl;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private final ObjectMapper json = new ObjectMapper();
  private final ConcurrentHashMap<String, Source> sources = new ConcurrentHashMap<>();

  @Autowired
  public MediaSourceService(SettingsService settingsService, MediaSourceRepository mediaSourceRepository,
                            @Value("${fintube.youtube.po-token-provider-url:}") String poTokenProviderUrl) {
    this.settingsService = settingsService;
    this.mediaSourceRepository = mediaSourceRepository;
    this.poTokenProviderUrl = poTokenProviderUrl;
  }

  MediaSourceService(SettingsService settingsService, MediaSourceRepository mediaSourceRepository) {
    this(settingsService, mediaSourceRepository, "");
  }

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

  private record ProbeResult(int exitCode, byte[] stdout, byte[] stderr) {}

  /** Load a valid persisted probe before invoking yt-dlp after a restart. */
  public Source source(String video) throws Exception {
    Source memory = sources.get(video);
    if (memory != null && !memory.expired()) return memory;
    Source persisted = load(video);
    if (persisted != null && !persisted.expired() && currentFormat(persisted)) {
      sources.put(video, persisted);
      return persisted;
    }
    return refresh(video);
  }

  /** Force a new yt-dlp probe, replacing the persisted source URL set. */
  public synchronized Source refresh(String video) throws Exception {
    var settings = settingsService.values(false);
    String bin = settings.getOrDefault("yt_dlp_path", "yt-dlp");
    List<String> command = new ArrayList<>(List.of(bin, "-J", "--no-playlist"));
    String proxy = proxyArgument(settings);
    if (proxy != null && !proxy.isBlank()) command.addAll(List.of("--proxy", proxy));
    String cookieFile = settings.get("cookie_file");
    if (cookieFile != null && !cookieFile.isBlank()) command.addAll(List.of("--cookies", cookieFile));
    String playerClient = settings.get("youtube_player_client");
    String poToken = settings.get("youtube_po_token");
    String providerArgs = providerArguments(settings);
    if (playerClient != null && !playerClient.isBlank()) command.addAll(List.of("--extractor-args", "youtube:player_client=" + playerClient));
    if (poToken != null && !poToken.isBlank()) command.addAll(List.of("--extractor-args", "youtube:po_token=" + poToken));
    // Provider plugins are discovered by yt-dlp itself. This option only forwards
    // their documented extractor arguments; it never executes a shell command.
    if (providerArgs != null && !providerArgs.isBlank()) command.addAll(List.of("--extractor-args", providerArgs));
    command.add("https://www.youtube.com/watch?v=" + URLEncoder.encode(video, StandardCharsets.UTF_8));
    ProbeResult result = runProbe(command, bin);
    if (result.exitCode() != 0 && providerArgs != null && !providerArgs.isBlank()) {
      // Provider plugins are optional outside the Compose deployment. Retry
      // without this provider only; cookies/manual tokens remain intact.
      int index = command.lastIndexOf(providerArgs);
      if (index > 0 && "--extractor-args".equals(command.get(index - 1))) { command.remove(index); command.remove(index - 1); }
      result = runProbe(command, bin);
    }
    if (result.exitCode() != 0) {
      String detail = probeError(result.stderr());
      throw new IllegalStateException("yt-dlp probe failed with exit code " + result.exitCode()
          + (detail.isBlank() ? "" : ": " + detail));
    }
    JsonNode root = json.readTree(result.stdout());
    Source selected = select(root);
    persist(video, selected);
    sources.put(video, selected);
    return selected;
  }

  private String providerArguments(java.util.Map<String, String> settings) {
    if (!"true".equalsIgnoreCase(settings.getOrDefault("youtube_po_token_provider_enabled", "true"))) return "";
    if (poTokenProviderUrl != null && !poTokenProviderUrl.isBlank()) {
      return "youtubepot-bgutilhttp:base_url=" + poTokenProviderUrl;
    }
    return settings.getOrDefault("youtube_po_token_provider_args", DEFAULT_PO_TOKEN_PROVIDER_ARGS);
  }

  private ProbeResult runProbe(List<String> command, String bin) throws Exception {
    LOG.info("event=YTDLP_PROBE_STARTED command={}", displayCommand(command));
    Process process = startProbe(command, bin);
    CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
    CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
    int exitCode = process.waitFor();
    byte[] output = stdout.join();
    byte[] error = stderr.join();
    if (exitCode != 0) {
      LOG.warn("event=YTDLP_PROBE_FAILED exitCode={} stderr={}", exitCode, probeError(error));
    } else {
      LOG.info("event=YTDLP_PROBE_SUCCEEDED");
    }
    return new ProbeResult(exitCode, output, error);
  }

  private static String probeError(byte[] bytes) {
    String value = new String(bytes, StandardCharsets.UTF_8).strip();
    return value.length() > 4000 ? value.substring(0, 4000) + "…" : value;
  }

  private static String displayCommand(List<String> command) {
    List<String> safe = new ArrayList<>(command);
    for (int index = 0; index < safe.size(); index++) {
      String argument = safe.get(index);
      if ("--proxy".equals(argument) && index + 1 < safe.size()) {
        safe.set(index + 1, redactProxy(safe.get(index + 1)));
      } else if (argument.startsWith("youtube:po_token=")) {
        safe.set(index, "youtube:po_token=<redacted>");
      }
    }
    return String.join(" ", safe);
  }

  private static String redactProxy(String proxy) {
    int schemeEnd = proxy.indexOf("://");
    int userInfoEnd = schemeEnd < 0 ? -1 : proxy.indexOf('@', schemeEnd + 3);
    if (userInfoEnd < 0) return proxy;
    return proxy.substring(0, schemeEnd + 3) + "<redacted>@" + proxy.substring(userInfoEnd + 1);
  }

  private static byte[] read(InputStream stream) {
    try (stream) {
      return stream.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Process startProbe(List<String> command, String bin) throws IOException {
    try {
      return new ProcessBuilder(command).redirectErrorStream(false).start();
    } catch (IOException e) {
      throw new IllegalStateException("yt-dlp executable is unavailable: " + bin
          + ". Install yt-dlp or set the yt_dlp_path setting to its absolute path.", e);
    }
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
  public Source select(JsonNode root) throws Exception {
    String quality = setting("stream_quality", "720").toLowerCase(Locale.ROOT);
    int ceiling = "best".equals(quality) || "best-compatible".equals(quality)
        ? Integer.MAX_VALUE : parseQuality(quality);
    List<String> preferredVideo = codecs("preferred_video_codecs", List.of("av1", "vp9", "h264"));
    List<String> preferredAudio = codecs("preferred_audio_codecs", List.of("opus", "aac"));
    Set<String> allowedVideo = allowed("allowed_video_codecs");
    Set<String> allowedAudio = allowed("allowed_audio_codecs");

    List<Candidate> all = new ArrayList<>();
    for (JsonNode f : root.path("formats")) {
      String vc = codecName(rawCodec(f, "vcodec"));
      String ac = codecName(rawCodec(f, "acodec"));
      if ("none".equals(vc) && "none".equals(ac) && likelyHlsAudio(f)) ac = "aac";
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
    for (Candidate candidate : progressive) {
      Candidate withTimeline = withTimeline(candidate);
      if (seekable(withTimeline)) return makeSource(root, withTimeline, null, true);
    }

    List<Candidate> videos = all.stream().filter(Candidate::hasVideo).filter(c -> !c.hasAudio())
        .filter(this::directPlayable).filter(c -> seekable(c) || isHls(c)).sorted(videoOrder).toList();
    List<Candidate> audios = all.stream().filter(c -> c.hasAudio() && !c.hasVideo())
        .filter(this::directPlayable).filter(c -> seekable(c) || isHls(c)).sorted(Comparator.comparingInt(Candidate::codecRank)
            .thenComparingDouble(Candidate::bitrate).reversed()).toList();
    for (Candidate videoCandidate : videos) {
      Candidate video = withTimeline(videoCandidate);
      if (!seekable(video)) continue;
      for (Candidate audioCandidate : audios) {
        Candidate audio = withTimeline(audioCandidate);
        if (seekable(audio)) return makeSource(root, video, audio, false);
      }
    }
    throw new IllegalStateException("no seekable fragmented representation available");
  }

  private Candidate withTimeline(Candidate candidate) throws Exception {
    if (seekable(candidate) || !isHls(candidate)) return candidate;
    URI playlist = uri(candidate.node().path("url").asText(null));
    if (playlist == null) return candidate;
    ArrayNode fragments = hlsFragments(playlist, 0);
    if (fragments.size() < 2) return candidate;
    ObjectNode copy = (ObjectNode) candidate.node().deepCopy();
    copy.set("fragments", fragments);
    return new Candidate(copy, candidate.height(), candidate.videoCodec(), candidate.audioCodec(), candidate.ext(), candidate.codecRank(), candidate.bitrate());
  }

  private ArrayNode hlsFragments(URI playlist, int depth) throws Exception {
    if (depth > 2) throw new IllegalStateException("HLS playlist nesting is too deep");
    HttpRequest request = HttpRequest.newBuilder(playlist).timeout(Duration.ofSeconds(30)).GET().build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() > 299)
      throw new IllegalStateException("HLS playlist request returned status " + response.statusCode());
    List<String> lines = response.body().lines().toList();
    for (int i = 0; i < lines.size(); i++) {
      if (!lines.get(i).startsWith("#EXT-X-STREAM-INF:")) continue;
      for (int j = i + 1; j < lines.size(); j++) {
        String variant = lines.get(j).trim();
        if (!variant.isBlank() && !variant.startsWith("#")) return hlsFragments(playlist.resolve(variant), depth + 1);
      }
    }
    if (lines.stream().anyMatch(line -> line.startsWith("#EXT-X-MAP:"))) return json.createArrayNode();
    ArrayNode fragments = json.createArrayNode();
    double duration = -1;
    for (String raw : lines) {
      String line = raw.trim();
      if (line.startsWith("#EXTINF:")) {
        int comma = line.indexOf(',');
        String value = comma < 0 ? line.substring(8) : line.substring(8, comma);
        try { duration = Double.parseDouble(value); } catch (NumberFormatException ignored) { duration = -1; }
      } else if (duration >= 0 && !line.isBlank() && !line.startsWith("#")) {
        ObjectNode fragment = fragments.addObject();
        fragment.put("url", playlist.resolve(line).toString());
        fragment.put("duration", duration);
        duration = -1;
      }
    }
    return fragments;
  }

  private Source makeSource(JsonNode root, Candidate video, Candidate audio, boolean progressive) {
    String format = progressive ? video.id() : video.id() + "+" + audio.id() + REMUX_FORMAT_VERSION;
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
  private static boolean currentFormat(Source source) {
    return source.progressive() || source.format().endsWith(REMUX_FORMAT_VERSION);
  }
  private boolean seekable(Candidate candidate) { return candidate.node().path("fragments").isArray() && candidate.node().path("fragments").size() > 1; }
  private static boolean isHls(Candidate candidate) { return isHls(candidate.node()); }
  private static boolean isHls(JsonNode format) {
    String protocol = format.path("protocol").asText("").toLowerCase(Locale.ROOT);
    return protocol.contains("m3u8") || format.path("url").asText("").contains("/manifest/hls_");
  }

  private static boolean likelyHlsAudio(JsonNode format) {
    if (!isHls(format)) return false;
    String id = format.path("format_id").asText("");
    return switch (id) {
      case "139", "140", "233", "234" -> true;
      default -> false;
    };
  }

  private boolean directPlayable(Candidate c) {
    if (c.node().path("url").asText("").isBlank() && !c.node().path("fragments").isArray()) return false;
    String protocol = c.node().path("protocol").asText("").toLowerCase(Locale.ROOT);
    return protocol.isBlank() || protocol.startsWith("http") || protocol.contains("m3u8");
  }

  private Source load(String video) throws Exception {
    MediaSourceEntity entity = mediaSourceRepository.findById(video).orElse(null);
    return entity == null ? null : decode(entity.getSourceJson());
  }

  private void persist(String video, Source source) throws Exception {
    String text = json.writeValueAsString(encode(source));
    String now = ApplicationClock.now();
    MediaSourceEntity entity = mediaSourceRepository.findById(video)
        .orElseGet(() -> new MediaSourceEntity(video, source.format(), text, source.duration(), null, now));
    entity.setFormatKey(source.format());
    entity.setSourceJson(text);
    entity.setDurationSeconds(source.duration());
    entity.setExpiresAt(source.expiresAt() == null ? null : source.expiresAt().toString());
    entity.setUpdatedAt(now);
    mediaSourceRepository.save(entity);
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
  private static String rawCodec(JsonNode format, String name) {
    JsonNode value = format.get(name);
    return value == null || value.isNull() ? "none" : value.asText("none");
  }
  private static Instant minExpiry(Instant a, Instant b) { if (a == null) return b; if (b == null) return a; return a.isBefore(b) ? a : b; }
  private static Instant expiry(JsonNode format) {
    String value = format.path("url").asText(""); Matcher matcher = EXPIRE.matcher(value);
    if (!matcher.find()) return null;
    try { return Instant.ofEpochSecond(Long.parseLong(matcher.group(1))); } catch (NumberFormatException ignored) { return null; }
  }
  private int parseQuality(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 720; } }
  private String setting(String key, String fallback) {
    String value = settingsService.value(key);
    return value == null ? fallback : value;
  }
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
