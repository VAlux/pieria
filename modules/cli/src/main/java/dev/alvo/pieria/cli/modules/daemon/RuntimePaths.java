package dev.alvo.pieria.cli.modules.daemon;

import dev.alvo.pieria.tools.os.AppDirs;

import java.nio.file.Path;

/**
 * CLI-local resolver for the daemon's runtime and log directories, used by the spawn fallback in
 * {@link OsDaemonProcess} to place the PID file and redirect daemon stdout/stderr.
 *
 * <p>Delegates OS defaults to {@link AppDirs} so a CLI-spawned daemon writes where an installed one
 * would. An explicit {@code runtimeDirOverride} (from {@code --runtime-dir}) wins; otherwise OS
 * defaults apply.
 */
public final class RuntimePaths {

  private final Path runtimeDir;
  private final Path logsDir;

  private RuntimePaths(Path runtimeDir, Path logsDir) {
    this.runtimeDir = runtimeDir;
    this.logsDir = logsDir;
  }

  /**
   * {@code runtimeDirOverride} may be {@code null}/blank to use the OS default.
   */
  public static RuntimePaths resolve(String runtimeDirOverride) {
    Path dataRoot = AppDirs.defaultDataRoot();
    Path runtime = (runtimeDirOverride != null && !runtimeDirOverride.isBlank())
      ? Path.of(runtimeDirOverride).toAbsolutePath()
      : AppDirs.defaultRuntimeDir(dataRoot).toAbsolutePath();
    return new RuntimePaths(runtime, AppDirs.defaultLogsDir(dataRoot).toAbsolutePath());
  }

  public Path runtimeDir() {
    return runtimeDir;
  }

  public Path logsDir() {
    return logsDir;
  }

  /**
   * {@code <runtimeDir>/pieria-daemon.pid}.
   */
  public Path pidFile() {
    return runtimeDir.resolve("pieria-daemon.pid");
  }
}
