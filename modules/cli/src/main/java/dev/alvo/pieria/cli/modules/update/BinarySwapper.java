package dev.alvo.pieria.cli.modules.update;

import dev.alvo.pieria.tools.io.FileOps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Swaps the staged native binaries into the install dir. Each replacement ends in a single
 * {@code ATOMIC_MOVE} of a fully-prepared file over the target, so there is never a window where the
 * target is missing. Originals are backed up first; any mid-swap failure rolls every already-swapped
 * binary back.
 *
 * <p>How the original is set aside depends on {@link Platform#locksRunningBinaries()}:
 *
 * <ul>
 *   <li><b>Unix</b> — the original is <em>copied</em> to {@code .bak} and then overwritten. A live
 *       Claude Code session's {@code pieria-gateway} keeps running off its old inode, which the
 *       replacement leaves untouched.
 *   <li><b>Windows</b> — the OS refuses to overwrite a running image file, but it does permit
 *       <em>renaming</em> one. So the original is moved to {@code .bak}, freeing the name for the
 *       new binary while any live process carries on executing from the renamed file. The
 *       consequence is that {@code .bak} usually cannot be deleted at the end of the run (the
 *       process holding it is still alive — including {@code pieria.exe} updating itself, which is
 *       by then running from its own {@code .bak}). {@link #sweepStale(Path)} clears them at the
 *       start of the next update, so the leftovers last exactly one run.
 * </ul>
 */
public final class BinarySwapper {

  private final Platform platform;

  public BinarySwapper(Platform platform) {
    this.platform = platform;
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
    sweepStale(binDir);

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
   * Stage {@code src} as a sibling temp file, harden + chmod it, set an existing target aside, then
   * atomically move the staged file over the target.
   */
  private Swapped swapOne(Path src, Path target, boolean executable) throws IOException {
    if (!Files.isRegularFile(src)) {
      throw new UpdateException("missing artifact in distribution: " + src.getFileName());
    }
    Path staged = target.resolveSibling(target.getFileName() + ".new");
    Path backup = freeBackupPath(target);
    Files.copy(src, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    if (executable) {
      platform.harden(staged);
      FileOps.makeExecutable(staged);
    }
    boolean hadOriginal = Files.exists(target);
    if (hadOriginal) {
      if (platform.locksRunningBinaries()) {
        // Renaming a running image is permitted where overwriting it is not.
        move(target, backup);
      } else {
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
      }
    }
    move(staged, target);
    return new Swapped(target, backup, hadOriginal);
  }

  /**
   * Pick a backup path for {@code target}. Normally {@code <name>.bak}, but a locked leftover from a
   * previous update can survive {@link #sweepStale(Path)} — on Windows, moving onto it would then
   * fail — so fall back to a uniquified sibling rather than aborting the swap.
   */
  private static Path freeBackupPath(Path target) {
    Path backup = target.resolveSibling(target.getFileName() + ".bak");
    if (!Files.exists(backup)) {
      return backup;
    }
    deleteQuietly(backup);
    return Files.exists(backup)
      ? target.resolveSibling(target.getFileName() + ".bak." + System.currentTimeMillis())
      : backup;
  }

  /**
   * Best-effort removal of {@code .new}/{@code .bak} leftovers in {@code binDir}. Anything still
   * locked is skipped and retried on the next update.
   */
  private static void sweepStale(Path binDir) {
    try (Stream<Path> entries = Files.list(binDir)) {
      entries.filter(BinarySwapper::isSwapLeftover).forEach(BinarySwapper::deleteQuietly);
    } catch (IOException ignored) {
      // best-effort: an unreadable install dir surfaces on the swap itself
    }
  }

  private static boolean isSwapLeftover(Path path) {
    String name = path.getFileName().toString();
    return name.endsWith(".new") || name.endsWith(".bak") || name.contains(".bak.");
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
