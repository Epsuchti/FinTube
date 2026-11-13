package ch.it4user.fintube.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessSupportTest {
  @Test
  void returnsExitCodeAndOutput() throws Exception {
    ProcessSupport.Result result = ProcessSupport.run(List.of("sh", "-c", "echo hello; echo oops >&2; exit 3"),
        Duration.ofSeconds(10), 1024);
    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(result.text()).contains("hello").contains("oops");
  }

  @Test
  void keepsOnlyTheEndOfLongOutput() throws Exception {
    ProcessSupport.Result result = ProcessSupport.run(
        List.of("sh", "-c", "i=0; while [ $i -lt 2000 ]; do echo line$i; i=$((i+1)); done"),
        Duration.ofSeconds(10), 64);
    String text = new String(result.output(), StandardCharsets.UTF_8);
    assertThat(result.output()).hasSize(64);
    assertThat(text).endsWith("line1999\n").doesNotContain("line0\n");
  }

  /** A child that inherits the pipe (like yt-dlp's Node helper) must not keep the caller blocked. */
  @Test
  void timeoutKillsTheToolAndItsChildren() {
    long started = System.nanoTime();
    assertThatThrownBy(() -> ProcessSupport.run(List.of("sh", "-c", "sleep 60 & sleep 60; wait"),
        Duration.ofMillis(500), 1024))
        .isInstanceOf(ProcessSupport.TimeoutExceeded.class);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
  }
}
