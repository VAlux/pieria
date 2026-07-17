package dev.alvo.pieria.cli.modules.config;

import dev.alvo.pieria.config.model.PieriaConfigFile;
import dev.alvo.pieria.config.toml.ConfigCodec;
import dev.alvo.pieria.config.toml.ConfigMerge;
import dev.alvo.pieria.config.toml.PieriaTomlLoader;
import dev.alvo.pieria.tools.os.AppDirs;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Loads the two-layer Pieria configuration: the global {@code config.toml} in the OS config dir
 * and a project's {@code .pieria/config.toml}, deep-merged with project &gt; global precedence
 * (code-baked record defaults fill whatever neither layer sets). Either file may be absent — an
 * absent layer contributes nothing.
 *
 * <p>The global config dir mirrors the daemon's {@code AppDataPathResolver} defaults
 * (macOS {@code ~/Library/Application Support/Pieria/config}, Windows {@code %APPDATA%\Pieria\config},
 * Linux {@code $XDG_CONFIG_HOME|~/.config/pieria}); {@code PIERIA_CONFIG_DIR} overrides it.
 *
 * <p>Loading is fail-loud: an unreadable or malformed file raises, because silently ignoring an
 * explicit config would be worse than stopping.
 */
public final class ProjectConfigLoader {

  public static final String PROJECT_CONFIG_DIR = ".pieria";
  public static final String CONFIG_FILE_NAME = "config.toml";

  private final Path globalConfigFile;
  private final Path projectConfigFile;
  private final PieriaTomlLoader loader = new PieriaTomlLoader();

  /**
   * Explicit file locations, used by {@link #create}.
   */
  public ProjectConfigLoader(Path globalConfigFile, Path projectConfigFile) {
    this.globalConfigFile = globalConfigFile;
    this.projectConfigFile = projectConfigFile;
  }

  /**
   * Production factory: global {@code config.toml} from the OS config dir (or
   * {@code PIERIA_CONFIG_DIR}), project file from {@code <projectDir>/.pieria/config.toml}.
   */
  public static ProjectConfigLoader create(Path projectDir) {
    return create(projectDir, null);
  }

  /**
   * As {@link #create(Path)}, but with an explicit config dir holding the global {@code config.toml}.
   * A {@code null} {@code configDir} falls back to {@code PIERIA_CONFIG_DIR} / the OS config dir.
   */
  public static ProjectConfigLoader create(Path projectDir, Path configDir) {
    Path globalDir = (configDir != null) ? configDir : defaultConfigDir();
    return new ProjectConfigLoader(
      globalDir.resolve(CONFIG_FILE_NAME),
      projectDir.resolve(PROJECT_CONFIG_DIR).resolve(CONFIG_FILE_NAME));
  }

  /**
   * Merge and bind the layered config. Absent files yield record defaults.
   */
  public PieriaConfigFile load() throws IOException {
    var global = loader.load(globalConfigFile);
    var project = loader.load(projectConfigFile);
    return ConfigCodec.bind(ConfigMerge.mergeAll(global, project), PieriaConfigFile.class);
  }

  public Path globalConfigFile() {
    return globalConfigFile;
  }

  public Path projectConfigFile() {
    return projectConfigFile;
  }

  /**
   * Mirror of the daemon's {@code AppDataPathResolver} config-dir defaults so the CLI reads the
   * same global file the daemon materializes. {@code PIERIA_CONFIG_DIR} wins when set.
   */
  static Path defaultConfigDir() {
    String override = System.getenv("PIERIA_CONFIG_DIR");
    if (override != null && !override.isBlank()) {
      return Path.of(override);
    }
    return AppDirs.defaultConfigDir(AppDirs.defaultDataRoot());
  }
}
