package dev.alvo.pieria.cli.modules.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the Pieria version stamp. Two sources, both best-effort: the version embedded in this CLI
 * binary (classpath {@code /version.txt}, surfaced by {@code pieria --version}), and the stamp
 * written next to an installed or staged set of binaries ({@code <bin>/version.txt}), used by
 * {@code update} to report old→new and to skip a redundant release update.
 */
public final class BuildInfo {

  public static final String UNKNOWN = "unknown";

  private BuildInfo() {
  }

  /**
   * Version of the running CLI, from the embedded {@code /version.txt}, or {@link #UNKNOWN}.
   */
  public static String current() {
    try (InputStream in = BuildInfo.class.getResourceAsStream("/version.txt")) {
      if (in != null) {
        String value = new String(in.readAllBytes()).strip();
        if (!value.isEmpty()) {
          return value;
        }
      }
    } catch (IOException ignored) {
      // fall through
    }
    return UNKNOWN;
  }

  /**
   * Version stamped in {@code <binDir>/version.txt}, or {@link #UNKNOWN} if absent/unreadable.
   */
  public static String readFrom(Path binDir) {
    Path stamp = binDir.resolve("version.txt");
    if (Files.isRegularFile(stamp)) {
      try {
        String value = Files.readString(stamp).strip();
        if (!value.isEmpty()) {
          return value;
        }
      } catch (IOException ignored) {
        // fall through
      }
    }
    return UNKNOWN;
  }

  public static boolean isKnown(String version) {
    return version != null && !version.isBlank() && !UNKNOWN.equals(version);
  }
}
