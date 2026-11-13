package ch.it4user.fintube.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounded execution of external tools (yt-dlp, ffmpeg, ffprobe).
 * Every run has a deadline, output is drained on a dedicated thread pool (never
 * the shared ForkJoin pool) and a timed-out tool is killed together with its
 * children, e.g. the Node process yt-dlp starts for YouTube's JS challenges.
 */
public final class ProcessSupport {
  /** Blocking pipe readers. Daemon threads, created on demand and reused. */
  public static final ExecutorService IO = Executors.newCachedThreadPool(r -> {
    Thread thread = new Thread(r, "fintube-process-io");
    thread.setDaemon(true);
    return thread;
  });
  private static final Duration DRAIN_GRACE = Duration.ofSeconds(10);

  private ProcessSupport() { }

  /** Result of a finished tool. {@code output} is the combined stdout/stderr, keeping only the last bytes. */
  public record Result(int exitCode, byte[] output) {
    public String text() { return new String(output, StandardCharsets.UTF_8).strip(); }
  }

  /** Thrown when a tool exceeded its deadline and was killed. */
  public static final class TimeoutExceeded extends IOException {
    TimeoutExceeded(String tool, Duration timeout) {
      super(tool + " timed out after " + timeout.toSeconds() + " seconds");
    }
  }

  /** Run with merged output; keeps only the last {@code maxOutputBytes} bytes of output. */
  public static Result run(List<String> command, Duration timeout, int maxOutputBytes)
      throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    CompletableFuture<byte[]> output = drainTail(process.getInputStream(), maxOutputBytes);
    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        kill(process);
        throw new TimeoutExceeded(command.isEmpty() ? "process" : command.get(0), timeout);
      }
      return new Result(process.exitValue(), await(output));
    } finally {
      if (process.isAlive()) kill(process);
    }
  }

  /** Read a stream to the end on the I/O pool, keeping the first {@code limit} bytes. */
  public static CompletableFuture<byte[]> drainHead(InputStream stream, int limit) {
    return CompletableFuture.supplyAsync(() -> {
      try (stream; ByteArrayOutputStream captured = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
          int remaining = limit - captured.size();
          if (remaining > 0) captured.write(buffer, 0, Math.min(read, remaining));
        }
        return captured.toByteArray();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }, IO);
  }

  /** Read a stream to the end on the I/O pool, keeping the last {@code limit} bytes. */
  public static CompletableFuture<byte[]> drainTail(InputStream stream, int limit) {
    return CompletableFuture.supplyAsync(() -> {
      byte[] ring = new byte[Math.max(1, limit)];
      long total = 0;
      try (stream) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
          for (int i = 0; i < read; i++) ring[(int) ((total + i) % ring.length)] = buffer[i];
          total += read;
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      if (total <= ring.length) return Arrays.copyOf(ring, (int) total);
      int start = (int) (total % ring.length);
      byte[] ordered = new byte[ring.length];
      System.arraycopy(ring, start, ordered, 0, ring.length - start);
      System.arraycopy(ring, 0, ordered, ring.length - start, start);
      return ordered;
    }, IO);
  }

  /**
   * Wait for a pipe reader after its process ended. A grandchild that inherited
   * the pipe can keep it open; then give up instead of blocking forever.
   */
  public static byte[] await(CompletableFuture<byte[]> output) throws InterruptedException {
    try {
      return output.get(DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      output.cancel(true);
      return new byte[0];
    } catch (java.util.concurrent.ExecutionException e) {
      return new byte[0];
    }
  }

  /** Kill a process and everything it started, then wait briefly for it to exit. */
  public static void kill(Process process) throws InterruptedException {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    process.waitFor(5, TimeUnit.SECONDS);
  }
}
