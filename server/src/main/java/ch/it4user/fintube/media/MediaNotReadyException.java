package ch.it4user.fintube.media;

import java.time.Duration;
import java.util.Locale;

/**
 * The video exists but cannot be played yet, e.g. a live event or premiere that
 * has not started. This is an expected, transient condition rather than a probe failure.
 */
public class MediaNotReadyException extends IllegalStateException {
  public static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(30);

  private final Duration retryAfter;

  public MediaNotReadyException(String message) {
    super(message);
    this.retryAfter = DEFAULT_RETRY_AFTER;
  }

  public Duration retryAfter() {
    return retryAfter;
  }

  /** True when yt-dlp's error output says the stream is scheduled but not started. */
  public static boolean matches(String probeError) {
    if (probeError == null) return false;
    String text = probeError.toLowerCase(Locale.ROOT);
    return text.contains("live event will begin")
        || text.contains("premieres in")
        || text.contains("this live event has not started")
        || text.contains("is not yet live");
  }
}
