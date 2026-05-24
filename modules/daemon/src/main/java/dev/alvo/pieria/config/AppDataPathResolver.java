package dev.alvo.pieria.config;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Resolves configured app-data paths into concrete filesystem locations. The database file honors
 * {@code pieria.db.path} when set; otherwise it falls back to the resolved database directory.
 */
@Component
public class AppDataPathResolver {

  private final AppDataProperties appData;
  private final PieriaProperties pieria;

  public record AppDataPaths(
    Path root,
    Path databaseDir,
    Path configDir,
    Path logsDir,
    Path runtimeDir,
    Path databaseFile) {
  }


  public AppDataPathResolver(AppDataProperties appData, PieriaProperties pieria) {
    this.appData = appData;
    this.pieria = pieria;
  }

  public AppDataPaths resolve() {
    Path dataRoot = configured(appData.root(), defaultDataRoot());
    Path databaseDir = configured(appData.databaseDir(), dataRoot);
    Path configDir = configured(appData.configDir(), defaultConfigDir(dataRoot));
    Path logsDir = configured(appData.logsDir(), defaultLogsDir(dataRoot));
    Path runtimeDir = configured(appData.runtimeDir(), defaultRuntimeDir(dataRoot));
    Path databaseFile = configuredDatabasePath(databaseDir);
    Path resolvedDatabaseFile = ":memory:".equals(databaseFile.toString())
      ? databaseFile
      : databaseFile.toAbsolutePath();

    return new AppDataPaths(dataRoot.toAbsolutePath(), databaseDir.toAbsolutePath(),
      configDir.toAbsolutePath(), logsDir.toAbsolutePath(), runtimeDir.toAbsolutePath(),
      resolvedDatabaseFile);
  }

  private Path configuredDatabasePath(Path databaseDir) {
    String configured = pieria.db() == null ? null : pieria.db().path();
    if (configured == null || configured.isBlank()) {
      return databaseDir.resolve("pieria.db");
    }

    return Path.of(configured);
  }

  private static Path configured(String value, Path fallback) {
    return value == null || value.isBlank() ? fallback : Path.of(value);
  }

  private static Path defaultDataRoot() {
    String os = osName();
    String home = System.getProperty("user.home", ".");
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

  private static Path defaultConfigDir(Path dataRoot) {
    String os = osName();
    String home = System.getProperty("user.home", ".");
    if (os.contains("win")) {
      return dataRoot.resolve("config");
    }
    if (os.contains("mac")) {
      return dataRoot.resolve("config");
    }
    String xdgConfig = System.getenv("XDG_CONFIG_HOME");
    return Path.of(xdgConfig == null || xdgConfig.isBlank() ? Path.of(home, ".config").toString() : xdgConfig,
      "pieria");
  }

  private static Path defaultLogsDir(Path dataRoot) {
    String os = osName();
    String home = System.getProperty("user.home", ".");
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

  private static Path defaultRuntimeDir(Path dataRoot) {
    String os = osName();
    if (!os.contains("win")) {
      String xdgRuntime = System.getenv("XDG_RUNTIME_DIR");
      if (xdgRuntime != null && !xdgRuntime.isBlank()) {
        return Path.of(xdgRuntime, "pieria");
      }
    }
    return dataRoot.resolve("run");
  }

  private static String osName() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
  }

}
