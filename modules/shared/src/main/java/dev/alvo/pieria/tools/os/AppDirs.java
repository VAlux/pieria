package dev.alvo.pieria.tools.os;

import java.nio.file.Path;

/**
 * XDG-style app-data path defaults: mac {@code ~/Library/Application Support/Pieria} etc., windows
 * {@code %APPDATA%/Pieria}, linux {@code XDG_DATA_HOME}/{@code XDG_CONFIG_HOME}/
 * {@code XDG_STATE_HOME}/{@code XDG_RUNTIME_DIR} with {@code ~/.local/...} fallbacks.
 *
 * <p>This is a distinct concept from the {@code PIERIA_HOME} install-root resolution in
 * {@link InstallHome} — do not conflate the two.
 */
public final class AppDirs {

  private AppDirs() {
  }

  public static Path defaultDataRoot() {
    OsFamily os = OsFamily.detect();
    String home = System.getProperty("user.home", ".");

    return switch (os) {
      case MAC -> Path.of(home, "Library", "Application Support", "Pieria");
      case WINDOWS -> {
        String appData = System.getenv("APPDATA");
        yield Path.of(appData == null || appData.isBlank() ? Path.of(home, "AppData", "Roaming").toString() : appData,
          "Pieria");
      }
      case null, default -> {
        String xdgData = System.getenv("XDG_DATA_HOME");
        yield Path.of(xdgData == null || xdgData.isBlank() ? Path.of(home, ".local", "share").toString() : xdgData,
          "pieria");
      }
    };
  }

  public static Path defaultConfigDir(Path dataRoot) {
    return switch (OsFamily.detect()) {
      case WINDOWS, MAC -> dataRoot.resolve("config");
      case null, default -> {
        String home = System.getProperty("user.home", ".");
        String xdgConfig = System.getenv("XDG_CONFIG_HOME");
        yield Path.of(xdgConfig == null || xdgConfig.isBlank() ? Path.of(home, ".config").toString() : xdgConfig,
          "pieria");
      }
    };
  }

  public static Path defaultLogsDir() {
    OsFamily os = OsFamily.detect();
    String home = System.getProperty("user.home", ".");

    return switch (os) {
      case MAC -> Path.of(home, "Library", "Logs", "Pieria");
      case WINDOWS -> {
        String localAppData = System.getenv("LOCALAPPDATA");
        yield Path.of(localAppData == null || localAppData.isBlank()
          ? Path.of(home, "AppData", "Local").toString()
          : localAppData, "Pieria", "logs");
      }
      case null, default -> {
        String xdgState = System.getenv("XDG_STATE_HOME");
        yield Path.of(xdgState == null || xdgState.isBlank() ? Path.of(home, ".local", "state").toString() : xdgState,
          "pieria", "logs");
      }
    };
  }

  public static Path defaultRuntimeDir(Path dataRoot) {
    OsFamily os = OsFamily.detect();
    if (os != OsFamily.WINDOWS) {
      String xdgRuntime = System.getenv("XDG_RUNTIME_DIR");
      if (xdgRuntime != null && !xdgRuntime.isBlank()) {
        return Path.of(xdgRuntime, "pieria");
      }
    }

    return dataRoot.resolve("run");
  }
}
