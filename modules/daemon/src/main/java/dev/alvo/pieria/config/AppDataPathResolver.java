package dev.alvo.pieria.config;

import dev.alvo.pieria.tools.os.AppDirs;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Resolves configured app-data paths into concrete filesystem locations. The database file honors
 * {@code pieria.db.path} when set; otherwise it falls back to the resolved database directory.
 */
@Component
public class AppDataPathResolver {

  private final AppDataProperties appData;
  private final PieriaProperties pieria;

  public AppDataPathResolver(AppDataProperties appData, PieriaProperties pieria) {
    this.appData = appData;
    this.pieria = pieria;
  }

  private static Path configured(String value, Path fallback) {
    return value == null || value.isBlank() ? fallback : Path.of(value);
  }

  private static Path defaultDataRoot() {
    return AppDirs.defaultDataRoot();
  }

  private static Path defaultConfigDir(Path dataRoot) {
    return AppDirs.defaultConfigDir(dataRoot);
  }

  private static Path defaultLogsDir() {
    return AppDirs.defaultLogsDir();
  }

  private static Path defaultRuntimeDir(Path dataRoot) {
    return AppDirs.defaultRuntimeDir(dataRoot);
  }

  public AppDataPaths resolve() {
    Path dataRoot = configured(appData.root(), defaultDataRoot());
    Path databaseDir = configured(appData.databaseDir(), dataRoot);
    Path configDir = configured(appData.configDir(), defaultConfigDir(dataRoot));
    Path logsDir = configured(appData.logsDir(), defaultLogsDir());
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

  public record AppDataPaths(
    Path root,
    Path databaseDir,
    Path configDir,
    Path logsDir,
    Path runtimeDir,
    Path databaseFile) {
  }

}
