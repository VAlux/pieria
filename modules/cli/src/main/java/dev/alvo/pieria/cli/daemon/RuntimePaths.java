package dev.alvo.pieria.cli.daemon;

import java.nio.file.Path;
import java.util.Locale;

/**
 * CLI-local resolver for the daemon's runtime and log directories, used by the spawn fallback in
 * {@link OsDaemonProcess} to place the PID file and redirect daemon stdout/stderr.
 *
 * <p>It mirrors the OS defaults of the daemon's own {@code AppDataPathResolver} so a CLI-spawned
 * daemon writes where an installed one would. An explicit {@code runtimeDirOverride} (from
 * {@code --runtime-dir}) wins; otherwise OS defaults apply. If/when {@code AppDataPathResolver} is
 * promoted to {@code :shared}, this small mirror can be replaced by it.
 */
public final class RuntimePaths {

  private final Path runtimeDir;
  private final Path logsDir;

  private RuntimePaths(Path runtimeDir, Path logsDir) {
    this.runtimeDir = runtimeDir;
    this.logsDir = logsDir;
  }

  /** {@code runtimeDirOverride} may be {@code null}/blank to use the OS default. */
  public static RuntimePaths resolve(String runtimeDirOverride) {
    Path runtime = (runtimeDirOverride != null && !runtimeDirOverride.isBlank())
      ? Path.of(runtimeDirOverride).toAbsolutePath()
      : defaultRuntimeDir().toAbsolutePath();
    return new RuntimePaths(runtime, defaultLogsDir().toAbsolutePath());
  }

  public Path runtimeDir() {
    return runtimeDir;
  }

  public Path logsDir() {
    return logsDir;
  }

  /** {@code <runtimeDir>/pieria-daemon.pid}. */
  public Path pidFile() {
    return runtimeDir.resolve("pieria-daemon.pid");
  }

  private static Path defaultRuntimeDir() {
    if (!os().contains("win")) {
      String xdgRuntime = System.getenv("XDG_RUNTIME_DIR");
      if (xdgRuntime != null && !xdgRuntime.isBlank()) {
        return Path.of(xdgRuntime, "pieria");
      }
    }
    return dataRoot().resolve("run");
  }

  private static Path defaultLogsDir() {
    String os = os();
    String home = home();
    if (os.contains("mac")) {
      return Path.of(home, "Library", "Logs", "Pieria");
    }
    if (os.contains("win")) {
      String localAppData = System.getenv("LOCALAPPDATA");
      return Path.of(localAppData == null || localAppData.isBlank()
        ? Path.of(home, "AppData", "Local").toString()
        : localAppData, "Pieria", "logs");
    }
    String xdgState = System.getenv("XDG_STATE_HOME");
    return Path.of(xdgState == null || xdgState.isBlank() ? Path.of(home, ".local", "state").toString() : xdgState,
      "pieria", "logs");
  }

  private static Path dataRoot() {
    String os = os();
    String home = home();
    if (os.contains("mac")) {
      return Path.of(home, "Library", "Application Support", "Pieria");
    }
    if (os.contains("win")) {
      String appData = System.getenv("APPDATA");
      return Path.of(appData == null || appData.isBlank() ? Path.of(home, "AppData", "Roaming").toString() : appData,
        "Pieria");
    }
    String xdgData = System.getenv("XDG_DATA_HOME");
    return Path.of(xdgData == null || xdgData.isBlank() ? Path.of(home, ".local", "share").toString() : xdgData,
      "pieria");
  }

  private static String os() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
  }

  private static String home() {
    return System.getProperty("user.home", ".");
  }
}
