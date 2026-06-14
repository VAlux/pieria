package dev.alvo.pieria.cli.modules.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Swaps the staged native binaries into the install dir. Each replacement is a single
 * {@code ATOMIC_MOVE} of a fully-prepared file over the target, so there is never a window where the
 * target is missing and a live Claude Code session's memory-mapped {@code pieria-gateway} keeps
 * running on its old inode. Originals are backed up first; any mid-swap failure rolls every
 * already-swapped binary back.
 */
public final class BinarySwapper {

  private final Platform platform;

  public BinarySwapper(Platform platform) {
    this.platform = platform;
  }

  private static void makeExecutable(Path path) {
    try {
      Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(path));
      perms.add(PosixFilePermission.OWNER_EXECUTE);
      perms.add(PosixFilePermission.GROUP_EXECUTE);
      perms.add(PosixFilePermission.OTHERS_EXECUTE);
      Files.setPosixFilePermissions(path, perms);
    } catch (UnsupportedOperationException | IOException ignored) {
      // Non-POSIX filesystem: exec bit is irrelevant.
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // best-effort
    }
  }

  /**
   * Swap all artifacts of {@code dist} into the install layout. Rolls back on failure.
   */
  public void swap(StagedDist dist, InstallLayout install) {
    swapNative(dist, install.binDir());
  }

  private void swapNative(StagedDist dist, Path binDir) {
    try {
      Files.createDirectories(binDir);
    } catch (IOException e) {
      throw new UpdateException("could not create install dir " + binDir + ": " + e.getMessage(), e);
    }

    List<Swapped> done = new ArrayList<>();
    try {
      for (String name : BinarySource.BINARIES) {
        String exe = platform.exeName(name);
        Path src = dist.binDir().resolve(exe);
        Path target = binDir.resolve(exe);
        done.add(swapOne(src, target, true));
      }
      // Carry the version stamp across (best-effort, not rolled back).
      copyVersionStamp(dist.binDir(), binDir);
    } catch (RuntimeException | IOException failure) {
      rollback(done);
      throw failure instanceof UpdateException ue ? ue
        : new UpdateException("binary swap failed: " + failure.getMessage(), failure);
    }
    done.forEach(s -> deleteQuietly(s.backup()));
  }

  /**
   * Stage {@code src} as a sibling temp file, harden + chmod it, back up an existing target, then
   * atomically move the staged file over the target.
   */
  private Swapped swapOne(Path src, Path target, boolean executable) throws IOException {
    if (!Files.isRegularFile(src)) {
      throw new UpdateException("missing artifact in distribution: " + src.getFileName());
    }
    Path staged = target.resolveSibling(target.getFileName() + ".new");
    Path backup = target.resolveSibling(target.getFileName() + ".bak");
    Files.copy(src, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    if (executable) {
      platform.harden(staged);
      makeExecutable(staged);
    }
    boolean hadOriginal = Files.exists(target);
    if (hadOriginal) {
      Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }
    move(staged, target);
    return new Swapped(target, backup, hadOriginal);
  }

  private void move(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void rollback(List<Swapped> done) {
    for (int i = done.size() - 1; i >= 0; i--) {
      Swapped s = done.get(i);
      try {
        if (s.hadOriginal() && Files.exists(s.backup())) {
          move(s.backup(), s.target());
        } else if (!s.hadOriginal()) {
          deleteQuietly(s.target());
        }
      } catch (IOException ignored) {
        // best-effort restore
      }
      deleteQuietly(s.target().resolveSibling(s.target().getFileName() + ".new"));
    }
  }

  private void copyVersionStamp(Path fromBin, Path toBin) {
    Path src = fromBin.resolve("version.txt");
    if (Files.isRegularFile(src)) {
      try {
        Files.copy(src, toBin.resolve("version.txt"), StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException ignored) {
        // version stamp is informational only
      }
    }
  }

  private record Swapped(Path target, Path backup, boolean hadOriginal) {
  }
}
