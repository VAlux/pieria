package dev.alvo.pieria.cli.modules.update;

import java.nio.file.Path;

/**
 * The per-OS seam for {@code pieria update}. Adding an OS means implementing this interface and
 * registering it in {@link PlatformSupport#detect()} — no caller changes. Service start/stop is
 * intentionally not here: it is already abstracted by {@code DaemonProcessController}
 * (launchd / systemd / Scheduled Task / spawn).
 */
public interface Platform {

  /**
   * Release-asset / {@code packaging/native} slug, e.g. {@code "macos-aarch64"}. Mirrors
   * {@code detect_platform()} in {@code packaging/install.sh}.
   */
  String slug();

  /**
   * Whether the release workflow publishes an asset for this {@link #slug()}. Only gates
   * {@link ReleaseSource} — a locally-built distribution ({@code --from}/{@code --from-build}) is
   * installable on any architecture, so the swap path itself is never blocked by this.
   */
  default boolean hasPublishedRelease() {
    return PlatformSupport.PUBLISHED_PLATFORMS.contains(slug());
  }

  /**
   * Map a logical binary name to the on-disk file name ({@code base} unchanged on Unix, {@code .exe}
   * appended on Windows).
   */
  default String exeName(String base) {
    return base;
  }

  /**
   * Extension (without the leading dot) of this platform's published release asset — {@code tar.gz}
   * for the Unix targets, {@code zip} for Windows. Must match what
   * {@code .github/workflows/release.yml} packages, or {@link ReleaseSource} builds a 404 URL.
   */
  default String archiveExtension() {
    return "tar.gz";
  }

  /**
   * Whether the OS holds an exclusive lock on a running executable's image file. False on Unix,
   * where a binary can simply be replaced under a live process; true on Windows, which forces
   * {@link BinarySwapper} to rename the old binary out of the way instead of copying it aside.
   */
  default boolean locksRunningBinaries() {
    return false;
  }

  /**
   * Make a freshly-written binary launchable: on macOS strip the {@code com.apple.quarantine} xattr
   * and ad-hoc codesign it so Gatekeeper does not block it. A no-op on platforms that need nothing.
   */
  void harden(Path binary);

  /**
   * Extract a release archive (in this platform's {@link #archiveExtension()} format) into
   * {@code destDir}, preserving its internal layout (the archive's top level is {@code bin/}).
   */
  void extractDistributionArchive(Path archive, Path destDir);
}
