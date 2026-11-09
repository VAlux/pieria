package dev.alvo.pieria.tools.os;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * OS-default {@code PIERIA_HOME} install root, used as the last-resort fallback once callers have
 * already checked the {@code PIERIA_HOME} env var and any resolvable symlink/self-executable path.
 * Unconditional {@code ~/.local/share/pieria} on non-Windows (deliberately not XDG-aware, unlike
 * {@link AppDirs}) — this is a distinct concept from the XDG app-data root and must not be
 * conflated with it.
 */
public final class InstallHome {

  private InstallHome() {
  }

  /** {@link #forExecutable} for the running daemon. */
  public static Path ofDaemon() {
    return forExecutable("pieria-daemon", System::getenv, Path.of(System.getProperty("user.home")), OsFamily.detect() == OsFamily.WINDOWS);
  }

  /**
   * The install root: {@code PIERIA_HOME} when set, else the parent of the {@code bin} directory
   * holding the running executable when it is named {@code executableBaseName}, else the default.
   */
  public static Path forExecutable(String executableBaseName, Function<String, String> env, Path userHome, boolean windows) {
    String configured = env.apply("PIERIA_HOME");
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured);
    }
    Path running = runningInstall(executableBaseName);
    return running != null ? running : defaultHome(env, userHome, windows);
  }

  private static Path runningInstall(String executableBaseName) {
    return ProcessHandle.current().info().command().map(Path::of).map(command -> {
      String name = command.getFileName().toString();
      if (!name.equals(executableBaseName) && !name.equals(executableBaseName + ".exe")) {
        return null;
      }
      try {
        Path bin = command.toRealPath().getParent();
        return bin != null && bin.getFileName().toString().equals("bin") ? bin.getParent() : null;
      } catch (IOException ignored) {
        return null;
      }
    }).orElse(null);
  }

  public static Path defaultHome(Function<String, String> env, Path userHome, boolean windows) {
    if (windows) {
      String localAppData = env.apply("LOCALAPPDATA");
      Path base = (localAppData != null && !localAppData.isBlank())
        ? Path.of(localAppData) : userHome.resolve("AppData").resolve("Local");
      return base.resolve("Pieria");
    }
    return userHome.resolve(".local").resolve("share").resolve("pieria");
  }
}
