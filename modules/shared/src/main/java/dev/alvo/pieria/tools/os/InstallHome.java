package dev.alvo.pieria.tools.os;

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
