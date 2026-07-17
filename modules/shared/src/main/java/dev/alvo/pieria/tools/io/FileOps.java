package dev.alvo.pieria.tools.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

public final class FileOps {

  private FileOps() {
  }

  /**
   * Creates {@code path}'s parent directory (and any missing ancestors) if it doesn't already
   * exist. A no-op if {@code path} has no parent.
   */
  public static void ensureParentDirectory(Path path) {
    Path parent = path.getParent();
    if (parent == null) {
      return;
    }
    try {
      Files.createDirectories(parent);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create directory: " + parent, e);
    }
  }

  /**
   * Ensures {@code path}'s parent directory exists, then writes {@code content} to it.
   */
  public static void writeFile(Path path, String content) {
    ensureParentDirectory(path);
    try {
      Files.writeString(path, content);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write file: " + path, e);
    }
  }

  /**
   * Ensures {@code path}'s parent directory exists, then writes {@code content} to it.
   */
  public static void writeFile(Path path, byte[] content) {
    ensureParentDirectory(path);
    try {
      Files.write(path, content);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write file: " + path, e);
    }
  }

  /**
   * Sets the owner/group/others execute bit on {@code path}. A no-op (silently swallowed) on
   * non-POSIX filesystems, where the exec bit is irrelevant.
   */
  public static void makeExecutable(Path path) {
    try {
      Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(path));
      perms.add(PosixFilePermission.OWNER_EXECUTE);
      perms.add(PosixFilePermission.GROUP_EXECUTE);
      perms.add(PosixFilePermission.OTHERS_EXECUTE);
      Files.setPosixFilePermissions(path, perms);
    } catch (UnsupportedOperationException | IOException ignored) {
      // Non-POSIX filesystem (e.g. Windows): exec+ bit is irrelevant there.
    }
  }

  /**
   * Reads {@code path} as UTF-8 text, returning {@code null} instead of throwing if it can't be
   * read (missing, unreadable, etc). Callers own their own logging around a {@code null} result.
   */
  public static String readTextQuietly(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException | UncheckedIOException e) {
      return null;
    }
  }
}
