package dev.alvo.pieria.tools.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a zip archive. Pure Java on purpose: the Unix targets can shell out to {@code tar}, but
 * Windows has no comparable always-present extractor that is safe to depend on from a native image
 * ({@code Expand-Archive} means a PowerShell round-trip), and {@code java.util.zip} is already in
 * the JDK.
 */
public final class ZipArchive {

  private ZipArchive() {
  }

  /**
   * Extract every entry of {@code zip} under {@code destDir}, creating intermediate directories and
   * overwriting existing files. Entries are rejected if they resolve outside {@code destDir}
   * ("zip slip"), so a malicious or malformed archive cannot write over arbitrary paths.
   *
   * @throws IOException if the archive cannot be read, or an entry escapes {@code destDir}
   */
  public static void extract(Path zip, Path destDir) throws IOException {
    Path root = destDir.toAbsolutePath().normalize();
    Files.createDirectories(root);
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        Path target = resolveSafely(root, entry.getName());
        if (entry.isDirectory()) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          copy(in, target);
        }
      }
    }
  }

  /**
   * Resolve an entry name against {@code root}, rejecting anything that escapes it via {@code ..}
   * segments or an absolute path.
   */
  private static Path resolveSafely(Path root, String entryName) throws IOException {
    Path target = root.resolve(entryName).normalize();
    if (!target.startsWith(root)) {
      throw new IOException("zip entry escapes the destination directory: " + entryName);
    }
    return target;
  }

  /**
   * Copy the current entry's bytes. {@code ZipInputStream} reports EOF at the entry boundary, so the
   * stream is deliberately not closed here — the caller keeps reading the next entry from it.
   */
  private static void copy(InputStream in, Path target) throws IOException {
    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
  }
}
