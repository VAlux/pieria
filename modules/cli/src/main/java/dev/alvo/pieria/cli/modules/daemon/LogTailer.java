package dev.alvo.pieria.cli.modules.daemon;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Portable, pure-Java tailing primitives for the daemon log files, used by
 * {@code pieria logs}. Deliberately free of system tools ({@code tail -f},
 * PowerShell {@code Get-Content}) so it behaves identically on macOS, Linux, and Windows and is
 * safe under GraalVM native image (plain file IO, no reflection).
 *
 * <p>All operations work directly against the on-disk files, so they keep working when the daemon
 * is down or has crashed. Lines are treated as UTF-8 and echoed verbatim — no levels or timestamps
 * are added.
 */
public final class LogTailer {

  private static final int BLOCK = 8 * 1024;

  /**
   * Newly read text and the byte offset to resume reading from.
   */
  public record Chunk(String text, long newPosition) {
  }

  private LogTailer() {
  }

  /**
   * The trailing {@code n} lines of {@code file}, read backwards in fixed blocks so large logs are
   * not loaded whole. Returns an empty list when the file is absent, empty, or {@code n <= 0}.
   */
  public static List<String> lastLines(Path file, int n) {
    if (n <= 0 || !Files.isRegularFile(file)) {
      return List.of();
    }
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      long length = raf.length();
      if (length == 0) {
        return List.of();
      }
      // Read backwards, accumulating bytes until we have seen n newlines (excluding a trailing one)
      // or reach the start of the file.
      long pos = length;
      int newlines = 0;
      List<byte[]> blocks = new ArrayList<>();
      while (pos > 0 && newlines <= n) {
        int chunk = (int) Math.min(BLOCK, pos);
        pos -= chunk;
        byte[] buf = new byte[chunk];
        raf.seek(pos);
        raf.readFully(buf);
        blocks.add(buf);
        for (int i = chunk - 1; i >= 0; i--) {
          // Ignore a trailing newline at the very end of the file so it does not count as a line.
          if (buf[i] == '\n' && !(pos + i == length - 1)) {
            newlines++;
            if (newlines > n) {
              break;
            }
          }
        }
      }
      byte[] joined = join(blocks);
      String text = new String(joined, StandardCharsets.UTF_8);
      List<String> lines = splitLines(text);
      return lines.size() <= n ? lines : lines.subList(lines.size() - n, lines.size());
    } catch (IOException e) {
      return List.of();
    }
  }

  /**
   * Current length of {@code file} in bytes, or {@code 0} if it does not exist.
   */
  public static long size(Path file) {
    try {
      return Files.isRegularFile(file) ? Files.size(file) : 0L;
    } catch (IOException e) {
      return 0L;
    }
  }

  /**
   * Bytes appended to {@code file} since {@code position}, decoded as UTF-8, plus the position to
   * resume from next time. If the file has shrunk below {@code position} (rotation/truncation) it is
   * re-read from the start. Returns empty text with the unchanged position when nothing is new.
   */
  public static Chunk readFrom(Path file, long position) {
    if (!Files.isRegularFile(file)) {
      return new Chunk("", position);
    }

    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      long length = raf.length();
      long from = position;
      if (length < position) {
        from = 0; // rotated or truncated — start over.
      }
      if (length <= from) {
        return new Chunk("", from);
      }
      byte[] buf = new byte[(int) Math.min(length - from, Integer.MAX_VALUE)];
      raf.seek(from);
      raf.readFully(buf);
      return new Chunk(new String(buf, StandardCharsets.UTF_8), length);
    } catch (IOException e) {
      return new Chunk("", position);
    }
  }

  private static byte[] join(List<byte[]> blocksNewestFirst) {
    int total = 0;
    for (byte[] b : blocksNewestFirst) {
      total += b.length;
    }
    byte[] out = new byte[total];
    int offset = 0;
    // blocks were collected newest-first; reverse to restore file order.
    for (int i = blocksNewestFirst.size() - 1; i >= 0; i--) {
      byte[] b = blocksNewestFirst.get(i);
      System.arraycopy(b, 0, out, offset, b.length);
      offset += b.length;
    }
    return out;
  }

  private static List<String> splitLines(String text) {
    // Strip a single trailing newline so it does not yield a spurious empty final line.
    String trimmed = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    if (trimmed.isEmpty()) {
      return List.of();
    }
    return new ArrayList<>(Arrays.asList(trimmed.split("\n", -1)));
  }
}
