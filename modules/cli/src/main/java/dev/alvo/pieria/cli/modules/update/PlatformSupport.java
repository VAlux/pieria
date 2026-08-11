package dev.alvo.pieria.cli.modules.update;

import dev.alvo.pieria.tools.os.OsFamily;
import java.util.List;
import java.util.Locale;

/**
 * Detects the host {@link Platform}. The single place that needs editing to add a new OS.
 */
public final class PlatformSupport {

  /**
   * Slugs the release workflow actually builds. Mirrors {@code SUPPORTED_PLATFORMS} in
   * {@code packaging/install.sh} and {@code $SupportedPlatforms} in {@code packaging/install.ps1};
   * keep all three in sync with {@code .github/workflows/release.yml}'s build matrix.
   */
  static final List<String> PUBLISHED_PLATFORMS =
    List.of("macos-aarch64", "linux-x86_64", "windows-x86_64");

  private PlatformSupport() {
  }

  public static Platform detect() {
    String os = OsFamily.osName().toLowerCase(Locale.ROOT);
    String arch = normalizeArch(OsFamily.osArch());
    if (os.contains("mac") || os.contains("darwin")) {
      return new MacOsPlatform(arch);
    }
    if (os.contains("win")) {
      return new WindowsPlatform(arch);
    }
    return new LinuxPlatform(arch);
  }

  /**
   * Normalize the JVM's {@code os.arch} to the release slug's arch component, matching
   * {@code packaging/install.sh}: {@code arm64/aarch64 -> aarch64}, {@code x86_64/amd64 -> x86_64}.
   */
  static String normalizeArch(String raw) {
    String arch = raw.toLowerCase(Locale.ROOT);
    return switch (arch) {
      case "aarch64", "arm64" -> "aarch64";
      case "x86_64", "amd64" -> "x86_64";
      default -> arch;
    };
  }
}
