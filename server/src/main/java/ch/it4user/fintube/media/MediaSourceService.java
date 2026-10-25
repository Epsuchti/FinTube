package ch.it4user.fintube.media;

import ch.it4user.fintube.core.ApplicationClock;
import ch.it4user.fintube.core.SettingsService;
import ch.it4user.fintube.api.contract.model.MediaPreferences;
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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves one deterministic representation for a video and persists the result.
 * The best compatible fragmented or adaptive representation is preferred;
 * progressive media is retained only as a playback compatibility fallback.
 */
@Service
public class MediaSourceService {
  private static final String DEFAULT_PO_TOKEN_PROVIDER_ARGS = "youtubepot-bgutilhttp:base_url=http://127.0.0.1:4416";
  private static final String REMUX_FORMAT_VERSION = "-tsv6";
  private static final String FMP4_FORMAT_VERSION = "-fmp4v1";
  private static final String INDEXED_FORMAT_VERSION = "-idxv1";
  private static final long MAX_INDEX_BYTES = 8L * 1024 * 1024;
  // Included in persisted source policy so a selector change invalidates
  // existing, still-unexpired low-quality selections after deployment.
  private static final String SELECTOR_POLICY_VERSION = "selector-v3";
  private static final double TIMELINE_TOLERANCE_SECONDS = 0.250d;
  private static final Duration PROBE_TIMEOUT = Duration.ofMinutes(2);
  private static final Logger LOG = LoggerFactory.getLogger(MediaSourceService.class);
  private static final Pattern EXPIRE = Pattern.compile("(?:^|[?&])expire=(\\d+)|/expire/(\\d+)");
  private final SettingsService settingsService;
  private final MediaSourceRepository mediaSourceRepository;
  private final String poTokenProviderUrl;
  private final ProxiedHttpClient externalHttp;
  private final ObjectMapper json = new ObjectMapper();
  private final ConcurrentHashMap<String, Source> sources = new ConcurrentHashMap<>();
  /** One probe at a time per video and policy; unrelated videos resolve in parallel. */
  private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

  @Autowired
  public MediaSourceService(SettingsService settingsService, MediaSourceRepository mediaSourceRepository,
                            @Value("${fintube.youtube.po-token-provider-url:}") String poTokenProviderUrl,
                            ProxiedHttpClient externalHttp) {
    this.settingsService = settingsService;
    this.mediaSourceRepository = mediaSourceRepository;
    this.poTokenProviderUrl = poTokenProviderUrl;
    this.externalHttp = externalHttp;
  }

  public MediaSourceService(SettingsService settingsService, MediaSourceRepository mediaSourceRepository,
                            String poTokenProviderUrl) {
    this(settingsService, mediaSourceRepository, poTokenProviderUrl, new ProxiedHttpClient(settingsService));
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
    private final double startSeconds;

    public Fragment(String id, URI url, double seconds) { this(id, url, null, seconds, 0d); }
    public Fragment(String id, URI url, URI audioUrl, double seconds) {
      this(id, url, audioUrl, seconds, 0d);
    }
    public Fragment(String id, URI url, URI audioUrl, double seconds, double startSeconds) {
      this.id = id;
      this.url = url;
      this.audioUrl = audioUrl;
      this.seconds = seconds > 0 ? seconds : 4d;
      this.startSeconds = Math.max(0d, startSeconds);
    }
    public String id() { return id; }
    public URI url() { return url; }
    public URI audioUrl() { return audioUrl; }
    public double seconds() { return seconds; }
    public double startSeconds() { return startSeconds; }
    public boolean hasSeparateAudio() { return audioUrl != null; }
  }

  /**
   * One independently segmented track of an indexed adaptive source. Video
   * tracks are listed as HLS variants, the audio track as their AAC rendition.
   */
  public record IndexedTrack(String id, String formatId, URI url, String container, String codec, String codecs,
                             boolean audio, int width, int height, double fps, long bandwidth, String dynamicRange,
                             long initEnd, List<AdaptiveIndex.Segment> segments) {
    public IndexedTrack {
      segments = List.copyOf(segments);
    }
    public IndexedTrack withId(String value) {
      return new IndexedTrack(value, formatId, url, container, codec, codecs, audio, width, height, fps, bandwidth,
          dynamicRange, initEnd, segments);
    }
    public IndexedTrack withUrl(URI value) {
      return new IndexedTrack(id, formatId, value, container, codec, codecs, audio, width, height, fps, bandwidth,
          dynamicRange, initEnd, segments);
    }
    public double duration() {
      return segments.isEmpty() ? 0 : segments.getLast().startSeconds() + segments.getLast().seconds();
    }
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
    private final int width;
    private final int height;
    private final double fps;
    private final long bandwidth;
    private final String codecs;
    private final URI videoInit;
    private final String selectionPolicy;
    private final List<IndexedTrack> tracks;

    /** Kept for callers compiled against the original vertical-slice API. */
    public Source(String format, List<Fragment> fragments, int duration) {
      this(format, format, null, fragments, duration, maxTarget(fragments), null, null, null, null, true,
          0, 0, 0, 0, null, null, null);
    }

    Source(String format, String videoFormat, String audioFormat, List<Fragment> fragments,
           int duration, int targetDuration, String videoCodec, String audioCodec,
           String container, Instant expiresAt, boolean progressive,
           int width, int height, double fps, long bandwidth, String codecs, URI videoInit,
           String selectionPolicy) {
      this(format, videoFormat, audioFormat, fragments, duration, targetDuration, videoCodec, audioCodec, container,
          expiresAt, progressive, width, height, fps, bandwidth, codecs, videoInit, selectionPolicy, List.of());
    }

    public Source(String format, String videoFormat, String audioFormat, List<Fragment> fragments,
           int duration, int targetDuration, String videoCodec, String audioCodec,
           String container, Instant expiresAt, boolean progressive,
           int width, int height, double fps, long bandwidth, String codecs, URI videoInit,
           String selectionPolicy, List<IndexedTrack> tracks) {
      this.tracks = tracks == null ? List.of() : List.copyOf(tracks);
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
      this.width = width;
      this.height = height;
      this.fps = fps;
      this.bandwidth = bandwidth;
      this.codecs = codecs;
      this.videoInit = videoInit;
      this.selectionPolicy = selectionPolicy;
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
    public int width() { return width; }
    public int height() { return height; }
    public double fps() { return fps; }
    public long bandwidth() { return bandwidth; }
    public String codecs() { return codecs; }
    public URI videoInit() { return videoInit; }
    public boolean fmp4() { return videoInit != null; }
    public String selectionPolicy() { return selectionPolicy; }
    /** Independently segmented tracks: video variants first, then the AAC track "a". */
    public List<IndexedTrack> tracks() { return tracks; }
    public boolean indexed() { return !tracks.isEmpty(); }
    public IndexedTrack track(String id) {
      for (IndexedTrack track : tracks) if (track.id().equals(id)) return track;
      return null;
    }
    public List<IndexedTrack> videoTracks() { return tracks.stream().filter(t -> !t.audio()).toList(); }
    public IndexedTrack audioTrack() { return tracks.stream().filter(IndexedTrack::audio).findFirst().orElse(null); }
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
    return source(video, null);
  }

  /** Resolve a source under the requesting user's media preferences. */
  public Source source(String video, MediaPreferences preferences) throws Exception {
    String policy = policyKey(preferences);
    String key = sourceKey(video, policy, preferences != null);
    Source memory = sources.get(key);
    if (memory != null && !memory.expired() && currentFormat(memory, policy)) {
      LOG.debug("event=MEDIA_SOURCE_CACHE_HIT video={} cache=memory format={} fragments={}",
          video, memory.format(), memory.fragments().size());
      return memory;
    }
    String memoryReason = memory == null ? "memory_miss"
        : memory.expired() ? "memory_expired" : "memory_format_changed";
    Source persisted = load(key);
    if (persisted != null && !persisted.expired() && currentFormat(persisted, policy)) {
      sources.put(key, persisted);
      LOG.debug("event=MEDIA_SOURCE_CACHE_HIT video={} cache=persisted format={} fragments={}",
          video, persisted.format(), persisted.fragments().size());
      return persisted;
    }
    String persistedReason = persisted == null ? "persisted_miss"
        : persisted.expired() ? "persisted_expired" : "persisted_format_changed";
    String reason = memory == null ? persistedReason : memoryReason;
    synchronized (lock(key)) {
      // Another request for the same video may have probed while this one waited.
      Source fresh = sources.get(key);
      if (fresh != null && fresh != memory && !fresh.expired() && currentFormat(fresh, policy)) return fresh;
      LOG.info("event=MEDIA_SOURCE_REFRESH_REQUIRED video={} reason={} memoryFormat={} memoryExpiresAt={} persistedFormat={} persistedExpiresAt={}",
          video, reason, formatOrNull(memory), expiresAtOrNull(memory), formatOrNull(persisted), expiresAtOrNull(persisted));
      return refresh(video, preferences, reason);
    }
  }

  private Object lock(String key) {
    if (locks.size() > 10_000) locks.clear();
    return locks.computeIfAbsent(key, ignored -> new Object());
  }

  /** Force a new yt-dlp probe, replacing the persisted source URL set. */
  public Source refresh(String video) throws Exception {
    return refresh(video, null, "explicit");
  }

  public Source refresh(String video, String reason) throws Exception {
    return refresh(video, null, reason);
  }

  public Source refresh(String video, MediaPreferences preferences, String reason) throws Exception {
    synchronized (lock(sourceKey(video, policyKey(preferences), preferences != null))) {
      return probe(video, preferences, reason);
    }
  }

  private Source probe(String video, MediaPreferences preferences, String reason) throws Exception {
    LOG.debug("event=MEDIA_SOURCE_REFRESH_STARTED video={} reason={}", video, reason);
    var settings = settingsService.values(false);
    String bin = settings.getOrDefault("yt_dlp_path", "yt-dlp");
    List<String> command = new ArrayList<>(List.of(bin, "-J", "--no-playlist"));
    String proxy = externalHttp.proxyArgument(settings);
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
    ProbeResult result = runProbe(command, bin, video, reason);
    if (result.exitCode() != 0 && providerArgs != null && !providerArgs.isBlank()) {
      // Provider plugins are optional outside the Compose deployment. Retry
      // without this provider only; cookies/manual tokens remain intact.
      int index = command.lastIndexOf(providerArgs);
      if (index > 0 && "--extractor-args".equals(command.get(index - 1))) {
        LOG.warn("event=YTDLP_PROBE_RETRY_WITHOUT_PROVIDER video={} reason={}", video, reason);
        command.remove(index); command.remove(index - 1);
      }
      result = runProbe(command, bin, video, reason);
    }
    if (result.exitCode() != 0) {
      String detail = probeError(result.stderr());
      if (MediaNotReadyException.matches(detail)) {
        throw new MediaNotReadyException("media is not available yet: " + detail);
      }
      throw new IllegalStateException("yt-dlp probe failed with exit code " + result.exitCode()
          + (detail.isBlank() ? "" : ": " + detail));
    }
    JsonNode root = json.readTree(result.stdout());
    Source selected = select(root, preferences);
    String key = sourceKey(video, policyKey(preferences), preferences != null);
    persist(key, selected);
    sources.put(key, selected);
    LOG.info("event=MEDIA_SOURCE_REFRESH_SUCCEEDED video={} reason={} format={} path={} videoFormat={} audioFormat={} codecs={} resolution={}x{} fragments={} indexedTracks={} durationSeconds={} progressive={} expiresAt={}",
        video, reason, selected.format(), selected.indexed() ? "indexed-fmp4" : selected.fmp4() ? "hls-fmp4" : selected.progressive() ? "progressive" : "mpegts",
        selected.videoFormat(), selected.audioFormat(), selected.codecs(), selected.width(), selected.height(),
        selected.fragments().size(), selected.tracks().size(), selected.duration(), selected.progressive(), selected.expiresAt());
    return selected;
  }

  private String providerArguments(java.util.Map<String, String> settings) {
    if (!"true".equalsIgnoreCase(settings.getOrDefault("youtube_po_token_provider_enabled", "true"))) return "";
    if (poTokenProviderUrl != null && !poTokenProviderUrl.isBlank()) {
      return "youtubepot-bgutilhttp:base_url=" + poTokenProviderUrl;
    }
    return settings.getOrDefault("youtube_po_token_provider_args", DEFAULT_PO_TOKEN_PROVIDER_ARGS);
  }

  private ProbeResult runProbe(List<String> command, String bin, String video, String reason) throws Exception {
    long started = System.nanoTime();
    LOG.info("event=YTDLP_PROBE_STARTED video={} reason={} command={}", video, reason, displayCommand(command));
    Process process = startProbe(command, bin);
    CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
    CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
    if (!process.waitFor(PROBE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
      byte[] output = stdout.join();
      byte[] error = stderr.join();
      long durationMs = (System.nanoTime() - started) / 1_000_000;
      LOG.warn("event=YTDLP_PROBE_TIMED_OUT video={} reason={} durationMs={} stdoutBytes={} stderr={}",
          video, reason, durationMs, output.length, probeError(error));
      throw new IllegalStateException("yt-dlp probe timed out after " + PROBE_TIMEOUT.toSeconds() + " seconds");
    }
    int exitCode = process.exitValue();
    byte[] output = stdout.join();
    byte[] error = stderr.join();
    long durationMs = (System.nanoTime() - started) / 1_000_000;
    if (exitCode != 0 && MediaNotReadyException.matches(probeError(error))) {
      LOG.info("event=YTDLP_PROBE_NOT_READY video={} reason={} durationMs={} stderr={}",
          video, reason, durationMs, probeError(error));
    } else if (exitCode != 0) {
      LOG.warn("event=YTDLP_PROBE_FAILED video={} reason={} exitCode={} durationMs={} stdoutBytes={} stderr={}",
          video, reason, exitCode, durationMs, output.length, probeError(error));
    } else {
      LOG.info("event=YTDLP_PROBE_SUCCEEDED video={} reason={} durationMs={} stdoutBytes={} stderrBytes={}",
          video, reason, durationMs, output.length, error.length);
    }
    return new ProbeResult(exitCode, output, error);
  }

  private static String formatOrNull(Source source) {
    return source == null ? null : source.format();
  }

  private static Instant expiresAtOrNull(Source source) {
    return source == null ? null : source.expiresAt();
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
  /** Select the best representation under the administrator's quality/codec policy. */
  public Source select(JsonNode root) throws Exception {
    return select(root, null);
  }

  public Source select(JsonNode root, MediaPreferences preferences) throws Exception {
    String quality = preference(preferences == null || preferences.getQuality() == null ? null : preferences.getQuality().getValue(), "stream_quality", "1080").toLowerCase(Locale.ROOT);
    boolean bestCompatible = "best-compatible".equals(quality);
    int ceiling = "best".equals(quality) || bestCompatible ? Integer.MAX_VALUE : parseQuality(quality);
    List<String> preferredVideo = codecs(preferences == null ? null : preferences.getPreferredVideoCodecs(), "preferred_video_codecs", List.of("h264", "vp9", "av1"));
    List<String> preferredAudio = codecs(preferences == null ? null : preferences.getPreferredAudioCodecs(), "preferred_audio_codecs", List.of("aac", "opus"));
    Set<String> allowedVideo = allowed("allowed_video_codecs");
    Set<String> allowedAudio = allowed("allowed_audio_codecs");

    // "everything" ignores the personal codec list; it is only used for the
    // H.264 compatibility variant that keeps non-VP9/AV1 devices on direct play.
    String videoId = root.path("id").asText("?");
    List<Candidate> everything = new ArrayList<>();
    List<Candidate> all = new ArrayList<>();
    java.util.Map<String, Integer> rejected = new java.util.TreeMap<>();
    java.util.Map<String, String> bestPerCodec = new java.util.TreeMap<>();
    for (JsonNode f : root.path("formats")) {
      String vc = codecName(rawCodec(f, "vcodec"));
      String ac = codecName(rawCodec(f, "acodec"));
      if ("none".equals(vc) && "none".equals(ac) && likelyHlsAudio(f)) ac = "aac";
      int h = f.path("height").asInt(0);
      String protocol = f.path("protocol").asText("?");
      String key = ("none".equals(vc) ? "audio:" + ac : vc) + "/" + protocol;
      String previous = bestPerCodec.get(key);
      int previousHeight = previous == null ? -1 : Integer.parseInt(previous.substring(0, previous.indexOf('p')));
      if (h > previousHeight) bestPerCodec.put(key, h + "p(" + f.path("format_id").asText() + ")");
      String reason = null;
      if (f.path("has_drm").asBoolean(false)) reason = "drm";
      else if (!allowedVideo.isEmpty() && !"none".equals(vc) && !allowedVideo.contains(vc)) reason = "admin_video_codec";
      else if (!allowedAudio.isEmpty() && !"none".equals(ac) && !allowedAudio.contains(ac)) reason = "admin_audio_codec";
      else if (h > ceiling) reason = "above_quality";
      else if (bestCompatible && !"none".equals(vc) && sdrRank(f) == 0) reason = "hdr";
      if (reason != null) {
        rejected.merge(reason, 1, Integer::sum);
        LOG.debug("event=MEDIA_SOURCE_FORMAT_REJECTED video={} format={} vcodec={} acodec={} height={} protocol={} reason={}",
            videoId, f.path("format_id").asText(), rawCodec(f, "vcodec"), rawCodec(f, "acodec"), h, protocol, reason);
        continue;
      }
      int rank = !"none".equals(vc) ? rank(vc, preferredVideo) : rank(ac, preferredAudio);
      Candidate candidate = new Candidate(f, h, vc, ac, f.path("ext").asText(""), rank, f.path("tbr").asDouble(0));
      everything.add(candidate);
      // The personal codec list is a whitelist: an unlisted codec (for example
      // AV1 on a VP9-only household) is never selected as the main source.
      if (candidate.hasVideo() && !preferredVideo.contains(vc)) {
        rejected.merge("not_in_codec_list", 1, Integer::sum);
        LOG.debug("event=MEDIA_SOURCE_FORMAT_REJECTED video={} format={} vcodec={} height={} protocol={} reason=not_in_codec_list",
            videoId, candidate.id(), rawCodec(f, "vcodec"), h, protocol);
        continue;
      }
      all.add(candidate);
    }
    LOG.info("event=MEDIA_SOURCE_SELECTION_STARTED video={} quality={} videoCodecs={} audioCodecs={} formats={} candidates={} rejected={} bestPerCodec={}",
        videoId, quality, preferredVideo, preferredAudio, root.path("formats").size(), all.size(), rejected, bestPerCodec);

    Comparator<Candidate> videoOrder = Comparator.comparingInt(Candidate::height).reversed()
        .thenComparing(Comparator.comparingInt(Candidate::codecRank).reversed())
        .thenComparing(Comparator.comparingInt((Candidate c) -> sdrRank(c.node())).reversed())
        .thenComparing(Comparator.comparingDouble((Candidate c) -> c.node().path("fps").asDouble(0)).reversed())
        .thenComparing(Comparator.comparingInt(MediaSourceService::deliveryRank).reversed())
        .thenComparing(Comparator.comparingDouble(Candidate::bitrate).reversed());
    Comparator<Candidate> audioOrder = Comparator.comparingInt(Candidate::codecRank).reversed()
        .thenComparing(Comparator.comparingInt(MediaSourceService::audioLanguageRank).reversed())
        .thenComparing(Comparator.comparingInt((Candidate c) -> c.id().contains("drc") ? 0 : 1).reversed())
        .thenComparing(Comparator.comparingInt(MediaSourceService::aacProfileRank).reversed())
        .thenComparing(Comparator.comparingDouble(Candidate::bitrate).reversed());
    List<Candidate> videos = all.stream().filter(Candidate::hasVideo)
        .filter(this::directPlayable).sorted(videoOrder).toList();
    List<Candidate> audios = all.stream().filter(c -> c.hasAudio() && !c.hasVideo())
        .filter(c -> "aac".equals(c.audioCodec()))
        .filter(this::directPlayable).filter(c -> seekable(c) || isHls(c)).sorted(audioOrder).toList();
    List<Candidate> indexableAudios = all.stream().filter(MediaSourceService::indexableAudio)
        .sorted(audioOrder).toList();
    if (LOG.isDebugEnabled()) {
      LOG.debug("event=MEDIA_SOURCE_VIDEO_ORDER video={} order={}", videoId,
          videos.stream().limit(12).map(MediaSourceService::describe).toList());
      LOG.debug("event=MEDIA_SOURCE_AUDIO_ORDER video={} indexable={} hls={}", videoId,
          indexableAudios.stream().map(MediaSourceService::describe).toList(), audios.stream().map(MediaSourceService::describe).toList());
    }
    if (indexableAudios.isEmpty())
      LOG.info("event=MEDIA_SOURCE_NO_INDEXABLE_AAC video={} reason=no_https_m4a_aac_format", videoId);
    java.util.Map<String, Candidate> timelines = new java.util.HashMap<>();
    java.util.Map<String, Object> indexes = new java.util.HashMap<>();
    for (Candidate videoCandidate : videos) {
      if (indexableVideo(videoCandidate)) {
        Source indexed = indexedSource(root, videoCandidate, indexableAudios, everything, ceiling, preferences, indexes);
        if (indexed != null) return indexed;
        LOG.debug("event=MEDIA_SOURCE_CANDIDATE_SKIPPED video={} format={} path=indexed", videoId, describe(videoCandidate));
        continue;
      }
      if (!seekable(videoCandidate) && !isHls(videoCandidate)) {
        LOG.debug("event=MEDIA_SOURCE_CANDIDATE_SKIPPED video={} format={} reason=not_segmented_or_indexable", videoId, describe(videoCandidate));
        continue;
      }
      Candidate video = timeline(videoCandidate, timelines);
      if (!seekable(video)) {
        LOG.debug("event=MEDIA_SOURCE_CANDIDATE_SKIPPED video={} format={} reason=no_hls_timeline", videoId, describe(videoCandidate));
        continue;
      }
      if (video.progressive()) {
        // Progressive fMP4 needs a separate init-map path; use paired HLS instead.
        if (video.node().path("fragments").path(0).path("initUrl").isMissingNode())
          return makeSource(root, video, null, true, preferences);
        continue;
      }
      boolean fmp4Video = !video.node().path("fragments").path(0).path("initUrl").isMissingNode();
      // VP9/AV1 cannot be carried in the MPEG-TS remux path.
      if (!fmp4Video && !"h264".equals(video.videoCodec())) {
        LOG.debug("event=MEDIA_SOURCE_CANDIDATE_SKIPPED video={} format={} reason=codec_not_allowed_in_mpegts", videoId, describe(videoCandidate));
        continue;
      }
      for (Candidate audioCandidate : audios) {
        Candidate audio = timeline(audioCandidate, timelines);
        if (!audio.node().path("fragments").path(0).path("initUrl").isMissingNode()) continue;
        if (seekable(audio) && matchingTimelines(video, audio)) {
          LOG.info("event=MEDIA_SOURCE_HLS_SELECTED video={} video_format={} audio_format={} fmp4={} segments={}",
              videoId, describe(video), audio.id(), fmp4Video, partsOf(video.node()).size());
          return makeSource(root, video, audio, false, preferences);
        }
        LOG.debug("event=MEDIA_SOURCE_HLS_PAIR_REJECTED video={} video_format={} audio_format={} videoSegments={} audioSegments={} reason=timelines_differ",
            videoId, video.id(), audio.id(), partsOf(video.node()).size(), partsOf(audio.node()).size());
      }
    }
    // Last resort for a plain HTTP H.264 + AAC pair that could not be indexed:
    // remux the complete files into one MPEG-TS segment. Only H.264 is valid in
    // MPEG-TS HLS, so VP9/AV1 never take this path.
    List<Candidate> completeVideos = all.stream().filter(Candidate::hasVideo).filter(c -> "h264".equals(c.videoCodec()))
        .filter(this::directPlayable).filter(c -> !isHls(c)).filter(c -> parts(c.node()).isEmpty()).sorted(videoOrder).toList();
    List<Candidate> completeAudios = all.stream().filter(c -> c.hasAudio() && !c.hasVideo())
        .filter(c -> "aac".equals(c.audioCodec())).filter(this::directPlayable).filter(c -> !isHls(c))
        .filter(c -> parts(c.node()).isEmpty()).sorted(audioOrder).toList();
    if (!completeVideos.isEmpty() && !completeAudios.isEmpty()) {
      LOG.warn("event=MEDIA_SOURCE_SINGLE_SEGMENT_FALLBACK video={} audio={} height={}",
          completeVideos.getFirst().id(), completeAudios.getFirst().id(), completeVideos.getFirst().height());
      return makeSource(root, completeVideos.getFirst(), completeAudios.getFirst(), false, preferences);
    }
    // Some authenticated YouTube player responses expose only a complete
    // progressive file. It cannot provide fragment-level seeking, but it is
    // still a playable last resort: the rendition service materializes it as
    // one VOD segment instead of rejecting an otherwise usable video.
    Candidate progressive = all.stream().filter(Candidate::progressive).filter(c -> !isHls(c))
        .filter(this::directPlayable).sorted(videoOrder).findFirst().orElse(null);
    if (progressive != null) {
      LOG.warn("event=MEDIA_SOURCE_LOW_COMPATIBILITY_FALLBACK format={} height={} bitrateKbps={} reason=no_adaptive_source",
          progressive.id(), progressive.height(), progressive.bitrate());
      return makeSource(root, progressive, null, true, preferences);
    }
    throw new IllegalStateException("no playable representation available for the configured codecs "
        + preferredVideo + " up to " + (ceiling == Integer.MAX_VALUE ? "best" : ceiling + "p"));
  }

  private static String describe(Candidate c) {
    return c.id() + ":" + (c.hasVideo() ? c.videoCodec() + ":" + c.height() + "p" + (c.node().path("fps").asInt(0) > 0 ? c.node().path("fps").asInt(0) : "") : c.audioCodec())
        + ":" + c.ext() + ":" + c.node().path("protocol").asText("?") + ":" + Math.round(c.bitrate()) + "k"
        + (sdrRank(c.node()) == 0 ? ":HDR" : "");
  }

  private Candidate timeline(Candidate candidate, java.util.Map<String, Candidate> memo) throws Exception {
    Candidate known = memo.get(candidate.id());
    if (known != null) return known;
    Candidate resolved;
    try { resolved = withTimeline(candidate); }
    catch (Exception failure) {
      LOG.warn("event=MEDIA_SOURCE_HLS_TIMELINE_FAILED format={} reason={}", candidate.id(), failure.toString());
      resolved = candidate;
    }
    memo.put(candidate.id(), resolved);
    return resolved;
  }

  // ------------------------------------------------------------ indexed adaptive tracks

  /** Single-file HTTP adaptive video that can be served by byte ranges. */
  static boolean indexableVideo(Candidate c) {
    if (!c.hasVideo() || c.hasAudio() || isHls(c.node()) || !c.node().path("fragments").isMissingNode()) return false;
    if (c.node().path("url").asText("").isBlank() || !httpProtocol(c.node())) return false;
    return ("mp4".equals(c.ext()) && ("h264".equals(c.videoCodec()) || "av1".equals(c.videoCodec())))
        || ("webm".equals(c.ext()) && "vp9".equals(c.videoCodec()));
  }

  static boolean indexableAudio(Candidate c) {
    if (c.hasVideo() || !"aac".equals(c.audioCodec()) || isHls(c.node()) || !c.node().path("fragments").isMissingNode()) return false;
    if (c.node().path("url").asText("").isBlank() || !httpProtocol(c.node())) return false;
    return "m4a".equals(c.ext()) || "mp4".equals(c.ext());
  }

  private static boolean httpProtocol(JsonNode format) {
    String protocol = format.path("protocol").asText("").toLowerCase(Locale.ROOT);
    return protocol.isBlank() || protocol.equals("https") || protocol.equals("http");
  }

  private static int deliveryRank(Candidate c) {
    return indexableVideo(c) ? 2 : isHls(c.node()) ? 1 : 0;
  }

  private Source indexedSource(JsonNode root, Candidate video, List<Candidate> audios, List<Candidate> everything,
                               int ceiling, MediaPreferences preferences, java.util.Map<String, Object> memo) {
    double duration = root.path("duration").asDouble(0);
    IndexedTrack primary = indexTrack(video, "v", duration, memo);
    if (primary == null) return null;
    IndexedTrack audio = null;
    for (Candidate candidate : audios) {
      audio = indexTrack(candidate, "a", duration, memo);
      if (audio != null) break;
    }
    if (audio == null) {
      LOG.warn("event=MEDIA_SOURCE_INDEXED_NO_AUDIO video={} reason=no_indexable_aac_track", video.id());
      return null;
    }
    List<IndexedTrack> tracks = new ArrayList<>();
    IndexedTrack compat = null;
    String compatReason = "h264".equals(primary.codec()) ? "primary_is_h264" : !compatVariantEnabled() ? "disabled" : null;
    if (compatReason == null) {
      int compatCeiling = Math.min(1080, ceiling);
      List<Candidate> h264 = everything.stream().filter(MediaSourceService::indexableVideo)
          .filter(c -> "h264".equals(c.videoCodec()) && c.height() <= compatCeiling)
          .sorted(Comparator.comparingInt(Candidate::height).reversed()
              .thenComparing(Comparator.comparingDouble((Candidate c) -> c.node().path("fps").asDouble(0)).reversed())
              .thenComparing(Comparator.comparingDouble(Candidate::bitrate).reversed())).toList();
      for (Candidate candidate : h264) {
        compat = indexTrack(candidate, "v", duration, memo);
        if (compat != null) break;
      }
      compatReason = compat != null ? "added" : h264.isEmpty() ? "no_https_h264_format" : "h264_index_failed";
    }
    // The H.264 variant is listed first: ffmpeg-based consumers (Jellyfin remux
    // or transcode) use the first variant, and players that can decode the
    // higher variant switch up to it on their own.
    if (compat != null) tracks.add(compat.withId("v0"));
    tracks.add(primary.withId("v" + tracks.size()));
    tracks.add(audio.withId("a"));
    String format = primary.formatId() + "+" + audio.formatId() + (compat == null ? "" : "~" + compat.formatId())
        + INDEXED_FORMAT_VERSION;
    Instant expiry = null;
    for (IndexedTrack track : tracks) expiry = minExpiry(expiry, expiry(track.url()));
    double total = duration > 0 ? duration : primary.duration();
    String codecs = primary.codecs() + "," + audio.codecs();
    LOG.info("event=MEDIA_SOURCE_INDEXED_SELECTED video={} format={} primary={} audio={} compat={} compatReason={} durationSeconds={} expiresAt={}",
        root.path("id").asText("?"), format, IndexedMediaService.describe(primary), IndexedMediaService.describe(audio),
        compat == null ? "none" : IndexedMediaService.describe(compat), compatReason, Math.round(total), expiry);
    return new Source(format, primary.formatId(), audio.formatId(), List.of(), (int) Math.ceil(total),
        targetDuration(tracks), primary.codec(), "aac", primary.container(), expiry, false,
        primary.width(), primary.height(), primary.fps(), primary.bandwidth() + audio.bandwidth(), codecs, null,
        policyKey(preferences), tracks);
  }

  private IndexedTrack indexTrack(Candidate candidate, String id, double duration, java.util.Map<String, Object> memo) {
    Object known = memo.get(candidate.id());
    if (known instanceof IndexedTrack track) return track;
    if (known != null) return null;
    try {
      URI url = uri(candidate.node().path("url").asText(null));
      String container = "webm".equals(candidate.ext()) ? "webm" : "mp4";
      AdaptiveIndex.Index index = fetchIndex(url, container, duration);
      if (index.segments().size() < 1) throw new IllegalStateException("index has no segments");
      boolean audio = !candidate.hasVideo();
      JsonNode node = candidate.node();
      double kbps = audio ? node.path("abr").asDouble(candidate.bitrate()) : node.path("vbr").asDouble(candidate.bitrate());
      if (kbps <= 0) kbps = candidate.bitrate();
      long bandwidth = (long) Math.ceil(Math.max(0, kbps) * 1300);
      if (bandwidth == 0) bandwidth = audio ? 160_000 : fallbackBandwidth(candidate.height());
      String raw = rawCodec(node, audio ? "acodec" : "vcodec");
      String codecs = audio ? codecTag(raw, "aac") : videoCodecTag(raw, candidate.videoCodec(), node);
      LOG.info("event=MEDIA_SOURCE_INDEXED_TRACK format={} container={} codecs={} initBytes={} segments={} indexSeconds={} firstSegmentBytes={}",
          describe(candidate), container, audio ? codecTag(raw, "aac") : videoCodecTag(raw, candidate.videoCodec(), node),
          index.initEnd(), index.segments().size(), Math.round(index.duration()), index.segments().getFirst().length());
      IndexedTrack track = new IndexedTrack(id, candidate.id(), url, container,
          audio ? candidate.audioCodec() : candidate.videoCodec(), codecs, audio,
          node.path("width").asInt(0), candidate.height(), node.path("fps").asDouble(0), bandwidth,
          node.path("dynamic_range").asText("SDR"), index.initEnd(), index.segments());
      memo.put(candidate.id(), track);
      return track;
    } catch (Exception failure) {
      memo.put(candidate.id(), Boolean.FALSE);
      LOG.warn("event=MEDIA_SOURCE_INDEX_FAILED format={} reason={}", describe(candidate), failure.toString());
      return null;
    }
  }

  /** Reads the beginning of an adaptive file until its MP4 sidx or WebM Cues are complete. */
  AdaptiveIndex.Index fetchIndex(URI url, String container, double duration) throws Exception {
    long want = 64 * 1024;
    long started = System.nanoTime();
    while (true) {
      HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30))
          .header("Range", "bytes=0-" + (want - 1)).GET().build();
      HttpResponse<InputStream> response = externalHttp.send(request, HttpResponse.BodyHandlers.ofInputStream());
      byte[] head;
      long total = -1;
      try (InputStream body = response.body()) {
        if (response.statusCode() != 200 && response.statusCode() != 206)
          throw new IllegalStateException("index request returned status " + response.statusCode());
        head = body.readNBytes((int) want);
        String range = response.headers().firstValue("Content-Range").orElse("");
        int slash = range.lastIndexOf('/');
        if (slash >= 0) try { total = Long.parseLong(range.substring(slash + 1).trim()); } catch (NumberFormatException ignored) { }
        if (total < 0 && response.statusCode() == 200) total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
      }
      LOG.debug("event=MEDIA_SOURCE_INDEX_FETCHED host={} container={} status={} bytes={} totalBytes={} durationMs={}",
          url.getHost(), container, response.statusCode(), head.length, total, (System.nanoTime() - started) / 1_000_000);
      try {
        return AdaptiveIndex.parse(container, head, total, duration);
      } catch (AdaptiveIndex.NeedMoreBytesException more) {
        LOG.debug("event=MEDIA_SOURCE_INDEX_NEEDS_MORE container={} have={} need={}", container, head.length, more.required());
        if (head.length < want || more.required() > MAX_INDEX_BYTES)
          throw new IllegalStateException("adaptive index is incomplete or too large");
        want = Math.max(more.required(), want * 2);
      }
    }
  }

  private boolean compatVariantEnabled() {
    String value = settingsService.value("playback_compat_variant");
    return value == null || value.isBlank() || Boolean.parseBoolean(value);
  }

  private static int targetDuration(List<IndexedTrack> tracks) {
    double max = 1;
    for (IndexedTrack track : tracks) for (AdaptiveIndex.Segment segment : track.segments()) max = Math.max(max, segment.seconds());
    return (int) Math.ceil(max);
  }

  private static long fallbackBandwidth(int height) {
    return height <= 720 ? 3_000_000 : height <= 1080 ? 6_000_000 : height <= 1440 ? 12_000_000 : 25_000_000;
  }

  /** RFC 6381 codec string for HLS CODECS. YouTube reports bare "vp9" for WebM. */
  static String videoCodecTag(String raw, String normalized, JsonNode format) {
    String value = raw == null ? "" : raw.trim();
    String lower = value.toLowerCase(Locale.ROOT);
    if (lower.startsWith("avc1.") || lower.startsWith("av01.") || lower.startsWith("vp09.") || lower.startsWith("hvc1.")) return value;
    if ("vp9".equals(normalized)) {
      boolean hdr = sdrRank(format) == 0 || lower.contains("vp9.2");
      int width = format.path("width").asInt(0), height = format.path("height").asInt(0);
      double fps = format.path("fps").asDouble(30);
      return String.format(Locale.ROOT, "vp09.%s.%02d.%s", hdr ? "02" : "00", vp9Level(width, height, fps), hdr ? "10" : "08");
    }
    if ("h264".equals(normalized)) return "avc1.640028";
    if ("av1".equals(normalized)) return "av01.0.08M.08";
    return value;
  }

  static int vp9Level(int width, int height, double fps) {
    long[][] levels = {{10, 36_864L, 829_440L}, {20, 122_880L, 4_608_000L}, {21, 245_760L, 9_216_000L},
        {30, 552_960L, 20_736_000L}, {31, 983_040L, 36_864_000L}, {40, 2_228_224L, 83_558_400L},
        {41, 2_228_224L, 160_432_128L}, {50, 8_912_896L, 311_951_360L}, {51, 8_912_896L, 588_251_136L},
        {52, 8_912_896L, 1_176_502_272L}, {60, 35_651_584L, 1_176_502_272L}, {61, 35_651_584L, 2_353_004_544L}};
    long picture = (long) Math.max(width, 1) * Math.max(height, 1);
    double rate = picture * (fps > 0 ? fps : 30);
    for (long[] level : levels) if (picture <= level[1] && rate <= level[2]) return (int) level[0];
    return 62;
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
    HttpResponse<String> response = externalHttp.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() > 299)
      throw new IllegalStateException("HLS playlist request returned status " + response.statusCode());
    List<String> lines = response.body().lines().toList();
    if (lines.stream().anyMatch(line -> line.startsWith("#EXT-X-BYTERANGE:")
        || line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-DISCONTINUITY")))
      return json.createArrayNode();
    for (int i = 0; i < lines.size(); i++) {
      if (!lines.get(i).startsWith("#EXT-X-STREAM-INF:")) continue;
      for (int j = i + 1; j < lines.size(); j++) {
        String variant = lines.get(j).trim();
        if (!variant.isBlank() && !variant.startsWith("#")) return hlsFragments(playlist.resolve(variant), depth + 1);
      }
    }
    URI init = null;
    for (String line : lines) if (line.startsWith("#EXT-X-MAP:")) {
      if (line.contains("BYTERANGE=")) return json.createArrayNode();
      Matcher match = Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
      if (match.find()) init = playlist.resolve(match.group(1));
    }
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
    if (init != null && !fragments.isEmpty()) ((ObjectNode) fragments.get(0)).put("initUrl", init.toString());
    return fragments;
  }

  private Source makeSource(JsonNode root, Candidate video, Candidate audio, boolean progressive, MediaPreferences preferences) {
    URI videoInit = uri(video.node().path("fragments").path(0).path("initUrl").asText(null));
    String format = progressive ? video.id() : video.id() + "+" + audio.id()
        + (videoInit == null ? REMUX_FORMAT_VERSION : FMP4_FORMAT_VERSION);
    List<JsonNode> videoParts = parts(video.node());
    if (videoParts.isEmpty()) videoParts = List.of(video.node());
    List<JsonNode> audioParts = audio == null ? List.of() : parts(audio.node());
    int count = videoParts.size();
    if (count == 0) throw new IllegalStateException("selected source has no downloadable media");
    List<Fragment> fragments = new ArrayList<>(count);
    double total = root.path("duration").asDouble(0);
    double position = 0d;
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
      double fragmentSeconds = seconds > 0 ? seconds : 4;
      fragments.add(new Fragment("v" + i, vu, au, fragmentSeconds, position));
      position += fragmentSeconds;
    }
    int target = 1;
    for (Fragment f : fragments) target = Math.max(target, (int) Math.ceil(f.seconds()));
    String rawVideoCodec = rawCodec(video.node(), "vcodec");
    String rawAudioCodec = audio == null ? rawCodec(video.node(), "acodec") : rawCodec(audio.node(), "acodec");
    if ("none".equals(rawAudioCodec) && audio != null && likelyHlsAudio(audio.node()))
      rawAudioCodec = aacProfileRank(audio) > 0 ? "mp4a.40.2" : "mp4a.40.5";
    int height = video.height();
    int width = video.node().path("width").asInt(0);
    double fps = video.node().path("fps").asDouble(0);
    double videoKbps = video.node().path("vbr").asDouble(video.bitrate());
    double audioKbps = audio == null ? 0 : audio.node().path("abr").asDouble(audio.bitrate());
    long bandwidth = (long) Math.ceil(Math.max(0, videoKbps + audioKbps) * 1200);
    if (bandwidth == 0) bandwidth = switch (height) {
      case 0 -> 8_000_000;
      default -> height <= 720 ? 6_000_000 : height <= 1080 ? 12_000_000 : height <= 1440 ? 20_000_000 : 35_000_000;
    };
    String codecTags = codecTag(rawVideoCodec, video.videoCodec()) + "," + codecTag(rawAudioCodec, audio == null ? video.audioCodec() : audio.audioCodec());
    return new Source(format, video.id(), audio == null ? null : audio.id(), fragments,
        (int) Math.ceil(total), target, video.videoCodec(), audio == null ? video.audioCodec() : audio.audioCodec(),
        video.ext(), expiry, progressive, width, height, fps, bandwidth, codecTags, videoInit, policyKey(preferences));
  }

  private List<JsonNode> parts(JsonNode format) {
    List<JsonNode> result = new ArrayList<>();
    if (format.path("fragments").isArray()) format.path("fragments").forEach(result::add);
    return result;
  }

  static boolean matchingTimelines(Candidate video, Candidate audio) {
    List<JsonNode> videoParts = partsOf(video.node());
    List<JsonNode> audioParts = partsOf(audio.node());
    if (videoParts.size() < 2 || videoParts.size() != audioParts.size()) return false;
    double videoPosition = 0;
    double audioPosition = 0;
    for (int index = 0; index < videoParts.size(); index++) {
      if (Math.abs(videoPosition - audioPosition) > TIMELINE_TOLERANCE_SECONDS) return false;
      double videoDuration = videoParts.get(index).path("duration").asDouble(0);
      double audioDuration = audioParts.get(index).path("duration").asDouble(0);
      if (videoDuration <= 0 || audioDuration <= 0
          || Math.abs(videoDuration - audioDuration) > TIMELINE_TOLERANCE_SECONDS) return false;
      videoPosition += videoDuration;
      audioPosition += audioDuration;
    }
    return Math.abs(videoPosition - audioPosition) <= TIMELINE_TOLERANCE_SECONDS;
  }

  private static List<JsonNode> partsOf(JsonNode format) {
    List<JsonNode> result = new ArrayList<>();
    if (format.path("fragments").isArray()) format.path("fragments").forEach(result::add);
    return result;
  }
  private boolean currentFormat(Source source, String policy) {
    return source.bandwidth() > 0 && source.codecs() != null
        && policy.equals(source.selectionPolicy())
        && (source.progressive() || source.format().endsWith(REMUX_FORMAT_VERSION)
            || source.format().endsWith(FMP4_FORMAT_VERSION)
            || (source.indexed() && source.format().endsWith(INDEXED_FORMAT_VERSION)));
  }

  private String policyKey() {
    return policyKey(null);
  }

  private String policyKey(MediaPreferences preferences) {
    return String.join("|", SELECTOR_POLICY_VERSION,
        preference(preferences == null || preferences.getQuality() == null ? null : preferences.getQuality().getValue(), "stream_quality", "1080"),
        preference(preferences == null ? null : preferences.getPreferredVideoCodecs(), "preferred_video_codecs", "h264,vp9,av1"),
        preference(preferences == null ? null : preferences.getPreferredAudioCodecs(), "preferred_audio_codecs", "aac,opus"),
        setting("allowed_video_codecs", ""), setting("allowed_audio_codecs", ""));
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
    int suffix = id.indexOf('-');
    String baseId = suffix < 0 ? id : id.substring(0, suffix);
    return switch (baseId) {
      case "139", "140", "233", "234" -> true;
      default -> false;
    };
  }

  private static int audioLanguageRank(Candidate candidate) {
    int preference = candidate.node().path("language_preference").asInt(-1);
    if (preference >= 0) return preference;
    String note = candidate.node().path("format_note").asText("").toLowerCase(Locale.ROOT);
    return note.contains("original") || note.contains("default") ? 1 : 0;
  }

  private static int aacProfileRank(Candidate candidate) {
    if (!"aac".equals(candidate.audioCodec())) return 0;
    String codec = rawCodec(candidate.node(), "acodec").toLowerCase(Locale.ROOT);
    if (codec.startsWith("mp4a.40.2")) return 2;
    if (codec.startsWith("mp4a.40.5") || codec.startsWith("mp4a.40.29")) return -1;
    String id = candidate.id().split("-", 2)[0];
    if ("140".equals(id) || "234".equals(id)) return 2;
    if ("139".equals(id) || "233".equals(id)) return -1;
    return 0;
  }

  private static String codecTag(String raw, String normalized) {
    if (raw != null && !raw.isBlank() && !"none".equalsIgnoreCase(raw)) return raw;
    return "aac".equals(normalized) ? "mp4a.40.2" : normalized;
  }

  private static int sdrRank(JsonNode format) {
    String range = format.path("dynamic_range").asText("SDR");
    return range == null || range.isBlank() || "SDR".equalsIgnoreCase(range) || "null".equals(range) ? 1 : 0;
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
    out.put("width", source.width()); out.put("height", source.height());
    out.put("fps", source.fps()); out.put("bandwidth", source.bandwidth());
    if (source.codecs() != null) out.put("codecs", source.codecs());
    if (source.videoInit() != null) out.put("videoInit", source.videoInit().toString());
    if (source.selectionPolicy() != null) out.put("selectionPolicy", source.selectionPolicy());
    if (source.expiresAt() != null) out.put("expiresAt", source.expiresAt().toString());
    if (source.indexed()) {
      ArrayNode tracks = out.putArray("tracks");
      for (IndexedTrack t : source.tracks()) {
        ObjectNode x = tracks.addObject();
        x.put("id", t.id()); x.put("formatId", t.formatId()); x.put("url", t.url().toString());
        x.put("container", t.container()); x.put("codec", t.codec()); x.put("codecs", t.codecs());
        x.put("audio", t.audio()); x.put("width", t.width()); x.put("height", t.height()); x.put("fps", t.fps());
        x.put("bandwidth", t.bandwidth()); x.put("dynamicRange", t.dynamicRange()); x.put("initEnd", t.initEnd());
        ArrayNode segments = x.putArray("segments");
        for (AdaptiveIndex.Segment segment : t.segments()) {
          ArrayNode row = segments.addArray();
          row.add(segment.start()); row.add(segment.end()); row.add(segment.startSeconds()); row.add(segment.seconds());
        }
      }
    }
    ArrayNode fragments = out.putArray("fragments");
    for (Fragment f : source.fragments()) {
      ObjectNode x = fragments.addObject(); x.put("id", f.id()); x.put("url", f.url().toString()); x.put("seconds", f.seconds());
      x.put("startSeconds", f.startSeconds());
      if (f.audioUrl() != null) x.put("audioUrl", f.audioUrl().toString());
    }
    return out;
  }

  private Source decode(String text) throws java.io.IOException {
    JsonNode x = json.readTree(text);
    List<Fragment> fragments = new ArrayList<>();
    double position = 0d;
    for (JsonNode f : x.path("fragments")) {
      double seconds = f.path("seconds").asDouble(4);
      double startSeconds = f.path("startSeconds").asDouble(position);
      fragments.add(new Fragment(f.path("id").asText(), uriRequired(f.path("url").asText()),
          uri(f.path("audioUrl").asText(null)), seconds, startSeconds));
      position = startSeconds + seconds;
    }
    Instant expiry = x.hasNonNull("expiresAt") ? Instant.parse(x.path("expiresAt").asText()) : null;
    List<IndexedTrack> tracks = new ArrayList<>();
    for (JsonNode t : x.path("tracks")) {
      List<AdaptiveIndex.Segment> segments = new ArrayList<>();
      for (JsonNode row : t.path("segments"))
        segments.add(new AdaptiveIndex.Segment(row.path(0).asLong(), row.path(1).asLong(), row.path(2).asDouble(), row.path(3).asDouble()));
      tracks.add(new IndexedTrack(t.path("id").asText(), t.path("formatId").asText(), uriRequired(t.path("url").asText()),
          t.path("container").asText("mp4"), t.path("codec").asText(), t.path("codecs").asText(), t.path("audio").asBoolean(),
          t.path("width").asInt(), t.path("height").asInt(), t.path("fps").asDouble(), t.path("bandwidth").asLong(),
          t.path("dynamicRange").asText("SDR"), t.path("initEnd").asLong(), segments));
    }
    return new Source(x.path("format").asText(), x.path("videoFormat").asText(x.path("format").asText()),
        x.path("audioFormat").isMissingNode() ? null : x.path("audioFormat").asText(), fragments,
        x.path("duration").asInt(), x.path("targetDuration").asInt(Source.maxTarget(fragments)),
        textOrNull(x, "videoCodec"), textOrNull(x, "audioCodec"), textOrNull(x, "container"), expiry,
        x.path("progressive").asBoolean(true), x.path("width").asInt(0), x.path("height").asInt(0),
        x.path("fps").asDouble(0), x.path("bandwidth").asLong(0), textOrNull(x, "codecs"),
        uri(x.path("videoInit").asText(null)), textOrNull(x, "selectionPolicy"), tracks);
  }

  private static URI uriRequired(String value) { URI result = uri(value); if (result == null) throw new IllegalArgumentException("persisted source has no URL"); return result; }
  private static URI uri(String value) { try { return value == null || value.isBlank() ? null : URI.create(value); } catch (IllegalArgumentException e) { return null; } }
  private static String textOrNull(JsonNode x, String name) { return x.hasNonNull(name) ? x.path(name).asText() : null; }
  private static String rawCodec(JsonNode format, String name) {
    JsonNode value = format.get(name);
    return value == null || value.isNull() ? "none" : value.asText("none");
  }
  private static Instant minExpiry(Instant a, Instant b) { if (a == null) return b; if (b == null) return a; return a.isBefore(b) ? a : b; }
  private static Instant expiry(URI url) {
    if (url == null) return null;
    Matcher matcher = EXPIRE.matcher(url.toString());
    if (!matcher.find()) return null;
    try { return Instant.ofEpochSecond(Long.parseLong(matcher.group(1) == null ? matcher.group(2) : matcher.group(1))); }
    catch (NumberFormatException ignored) { return null; }
  }
  private static Instant expiry(JsonNode format) {
    String value = format.path("url").asText(""); Matcher matcher = EXPIRE.matcher(value);
    if (!matcher.find()) return null;
    try { return Instant.ofEpochSecond(Long.parseLong(matcher.group(1) == null ? matcher.group(2) : matcher.group(1))); }
    catch (NumberFormatException ignored) { return null; }
  }
  private int parseQuality(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 1080; } }
  private String setting(String key, String fallback) {
    String value = settingsService.value(key);
    return value == null ? fallback : value;
  }
  private List<String> codecs(String preference, String key, List<String> fallback) {
    String value = preference(preference, key, String.join(",", fallback));
    List<String> result = Arrays.stream(value.split(",")).map(MediaSourceService::codecName).filter(s -> !s.isBlank()).toList();
    return result.isEmpty() ? fallback : result;
  }
  private String preference(String value, String legacyKey, String fallback) {
    return value == null || value.isBlank() ? setting(legacyKey, fallback) : value;
  }
  private static String sourceKey(String video, String policy, boolean userSpecific) {
    if (!userSpecific) return video;
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(policy.getBytes(StandardCharsets.UTF_8));
      return video + "|" + java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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
