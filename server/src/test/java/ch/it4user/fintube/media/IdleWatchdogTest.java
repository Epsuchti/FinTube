package ch.it4user.fintube.media;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdleWatchdogTest {
  /** Blocks in read() like a stalled socket until it is closed. */
  static final class StalledStream extends InputStream {
    private final CountDownLatch closed = new CountDownLatch(1);
    @Override public int read() throws IOException {
      try { closed.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      throw new IOException("stream closed");
    }
    @Override public void close() { closed.countDown(); }
  }

  @Test
  void stalledReadIsAbortedAfterTheIdleTimeout() {
    StalledStream body = new StalledStream();
    long started = System.nanoTime();
    try (var watchdog = new FragmentManager.IdleWatchdog(body, 200)) {
      assertThatThrownBy(body::read).isInstanceOf(IOException.class);
      assertThat(watchdog.fired()).isTrue();
    }
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void pausedDownloadsAreNotTreatedAsStalled() throws Exception {
    StalledStream body = new StalledStream();
    try (var watchdog = new FragmentManager.IdleWatchdog(body, 100)) {
      watchdog.pause();
      Thread.sleep(400);
      assertThat(watchdog.fired()).isFalse();
      watchdog.resume();
      Thread.sleep(400);
      assertThat(watchdog.fired()).isTrue();
    }
  }
}
