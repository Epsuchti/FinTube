package ch.it4user.fintube.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapFilesTest {
  private static final int SIZE = 8 * 1024 * 1024;

  @TempDir Path dir;

  @Test
  void roundTripsLargeFilesWithoutPinningDirectMemory() throws Exception {
    byte[] data = new byte[SIZE];
    new Random(42).nextBytes(data);
    Path file = dir.resolve("fragment.bin");
    long before = directBytes();

    byte[][] read = new byte[1][];
    Thread worker = new Thread(() -> {
      try {
        try (OutputStream out = HeapFiles.newOutputStream(file)) { out.write(data); }
        read[0] = HeapFiles.readAllBytes(file);
      } catch (Exception e) { throw new RuntimeException(e); }
    });
    worker.start();
    worker.join();

    assertArrayEquals(data, read[0]);
    // Files.readAllBytes would leave an 8 MiB direct buffer cached on that thread.
    assertTrue(directBytes() - before < SIZE / 2, "large reads/writes must not use temporary direct buffers");
  }

  private static long directBytes() {
    return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
        .filter(pool -> "direct".equals(pool.getName())).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
  }
}
