package dev.alvo.pieria.cli.modules.update;

import java.nio.file.Path;

/**
 * The per-OS seam for {@code pieria update}. macOS is the only fully-supported platform today;
 * adding Linux or Windows means implementing this interface and registering it in
 * {@link PlatformSupport#detect()} — no caller changes. Service start/stop is intentionally not
 * here: it is already abstracted by {@code DaemonProcess} (launchd / systemd / spawn).
 */
public interface Platform {

  /**
   * Release-asset / {@code packaging/native} slug, e.g. {@code "macos-aarch64"}. Mirrors
   * {@code detect_platform()} in {@code packaging/install.sh}.
   */
  String slug();

  /**
   * Whether {@code pieria update} can run on this platform yet. Unsupported platforms still resolve
   * a {@link #slug()} (useful for messages) but must not be driven through the swap path.
   */
  default boolean supported() {
    return true;
  }

  /**
   * Map a logical binary name to the on-disk file name ({@code base} unchanged on Unix, {@code .exe}
   * appended on Windows).
   */
  default String exeName(String base) {
    return base;
  }

  /**
   * Make a freshly-written binary launchable: on macOS strip the {@code com.apple.quarantine} xattr
   * and ad-hoc codesign it so Gatekeeper does not block it. A no-op on platforms that need nothing.
   */
  void harden(Path binary);

  /**
   * Extract a {@code .tar.gz} release archive into {@code destDir}, preserving its internal layout
   * (the archive's top level is {@code bin/}).
   */
  void extractTarGz(Path archive, Path destDir);
}
