package ch.it4user.fintube.integration;

import ch.it4user.fintube.core.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tells the viewer in Jellyfin that SponsorBlock segments were removed. The
 * playlist is requested before Jellyfin reports the session as playing, so the
 * matching session is looked up a few times shortly after playback starts.
 * Each Jellyfin session is notified at most once per video.
 */
@Service
public class SponsorBlockNotifier {
  private static final Logger LOG = LoggerFactory.getLogger(SponsorBlockNotifier.class);
  private static final long[] ATTEMPT_DELAYS_SECONDS = {4, 8, 15, 30};
  private static final long MESSAGE_TIMEOUT_MS = 8_000;
  private final JellyfinClient jellyfin;
  private final SettingsService settings;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread thread = new Thread(r, "fintube-sponsor-notifier");
    thread.setDaemon(true);
    return thread;
  });
  /** session|video -> time notified; also guards against duplicate scheduling. */
  private final ConcurrentHashMap<String, Instant> notified = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Boolean> scheduled = new ConcurrentHashMap<>();

  public SponsorBlockNotifier(JellyfinClient jellyfin, SettingsService settings) {
    this.jellyfin = jellyfin;
    this.settings = settings;
  }

  /**
   * @param segments       sponsor segments SponsorBlock reported
   * @param removed        segments actually removed from the stream
   * @param removedSeconds playback time removed
   */
  public void playbackStarted(String videoId, Path libraryPath, int segments, int removed, double removedSeconds) {
    if (segments <= 0 || !enabled()) return;
    String key = videoId + "|" + libraryPath;
    if (scheduled.putIfAbsent(key, Boolean.TRUE) != null) return; // Jellyfin probes, then plays: one lookup run
    String text = message(segments, removed, removedSeconds);
    schedule(videoId, libraryPath, text, key, 0);
  }

  static String message(int segments, int removed, double removedSeconds) {
    String plural = segments == 1 ? "" : "s";
    if (removed <= 0)
      return String.format(Locale.ROOT, "%d sponsor segment%s found in this video, but it could not be removed without re-encoding.", segments, plural);
    String time = duration(removedSeconds);
    if (removed < segments)
      return String.format(Locale.ROOT, "%d of %d sponsor segments removed (%s skipped).", removed, segments, time);
    return String.format(Locale.ROOT, "%d sponsor segment%s removed (%s skipped).", removed, plural, time);
  }

  static String duration(double seconds) {
    long total = Math.round(seconds);
    return total >= 60 ? String.format(Locale.ROOT, "%d:%02d min", total / 60, total % 60) : total + " s";
  }

  private void schedule(String videoId, Path libraryPath, String text, String key, int attempt) {
    if (attempt >= ATTEMPT_DELAYS_SECONDS.length) {
      scheduled.remove(key);
      LOG.info("event=SPONSOR_NOTIFY_NO_SESSION video={} reason=no_playing_jellyfin_session_found", videoId);
      return;
    }
    scheduler.schedule(() -> {
      try {
        if (!deliver(videoId, libraryPath, text)) schedule(videoId, libraryPath, text, key, attempt + 1);
        else scheduled.remove(key);
      } catch (RuntimeException failure) {
        scheduled.remove(key);
        LOG.warn("event=SPONSOR_NOTIFY_FAILED video={} reason={}", videoId, failure.toString());
      }
    }, ATTEMPT_DELAYS_SECONDS[attempt], TimeUnit.SECONDS);
  }

  /** Returns true once at least one playing session was found (notified or not capable). */
  private boolean deliver(String videoId, Path libraryPath, String text) {
    var item = jellyfin.findItem(videoId, libraryPath).orElse(null);
    if (item == null) return false;
    var sessions = jellyfin.sessionsPlaying(item.id());
    if (sessions.isEmpty()) return false;
    cleanup();
    for (var session : sessions) {
      String key = session.id() + "|" + videoId;
      if (notified.containsKey(key)) continue;
      notified.put(key, Instant.now());
      if (!session.supportsMessages()) {
        LOG.info("event=SPONSOR_NOTIFY_UNSUPPORTED video={} client=\"{}\" device=\"{}\" reason=client_cannot_display_messages",
            videoId, session.client(), session.deviceName());
        continue;
      }
      var result = jellyfin.sendMessage(session.id(), "SponsorBlock", text, MESSAGE_TIMEOUT_MS);
      if (result.success())
        LOG.info("event=SPONSOR_NOTIFY_SENT video={} client=\"{}\" device=\"{}\" user=\"{}\" text=\"{}\"",
            videoId, session.client(), session.deviceName(), session.userName(), text);
      else
        LOG.warn("event=SPONSOR_NOTIFY_FAILED video={} client=\"{}\" status={} reason={}",
            videoId, session.client(), result.statusCode(), result.message());
    }
    return true;
  }

  private void cleanup() {
    Instant cutoff = Instant.now().minusSeconds(6 * 3600);
    notified.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
  }

  private boolean enabled() {
    String value = settings.value("sponsorblock_notify");
    return value == null || value.isBlank() || Boolean.parseBoolean(value);
  }
}
