package ch.it4user.fintube.media;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * Whole-file reads and writes for media fragments that never touch NIO's temporary direct buffers.
 *
 * <p>{@code Files.readAllBytes} and a large {@code write} to {@code Files.newOutputStream} hand the
 * whole array to a {@code FileChannel}, which copies it through a direct buffer of the same size and
 * then keeps that buffer cached on the calling thread. With multi-megabyte fragments and dozens of
 * request and fill threads, those cached buffers fill {@code MaxDirectMemorySize}, and the next
 * direct allocation anywhere fails with {@code OutOfMemoryError: Direct buffer memory} — in
 * production that was H2's background compaction, which then closes the database. The classic
 * {@code FileInputStream}/{@code FileOutputStream} copy through the native stack instead.
 */
public final class HeapFiles {
  private HeapFiles() { }

  public static byte[] readAllBytes(Path path) throws IOException {
    try (InputStream in = new FileInputStream(path.toFile())) {
      return in.readAllBytes();
    }
  }

  public static OutputStream newOutputStream(Path path) throws IOException {
    return new FileOutputStream(path.toFile());
  }
}
