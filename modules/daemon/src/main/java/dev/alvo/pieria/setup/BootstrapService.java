package dev.alvo.pieria.setup;

import dev.alvo.pieria.config.AppDataPathResolver;
import dev.alvo.pieria.config.AppDataPathResolver.AppDataPaths;
import dev.alvo.pieria.config.FirstRunProperties;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.StorageProperties;
import dev.alvo.pieria.model.ModelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Idempotent setup service that runs at daemon startup. It creates the app-data directory set, probes
 * model-provider reachability when configured, and exposes a {@link SetupState} snapshot for the
 * {@code /pieria-status} endpoint.
 */
@Service
public class BootstrapService implements ApplicationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(BootstrapService.class);

  /**
   * Bundled template dumped to {@link AppDataPaths#configDir()} so users have a config to edit.
   */
  static final String DEFAULT_CONFIG_RESOURCE = "config/pieria-default.properties";
  /**
   * Filename of the dumped config; matches the {@code spring.config.import} location in application.properties.
   */
  static final String CONFIG_FILE_NAME = "pieria.properties";
  /**
   * Bundled template of the layered TOML config read by the CLI (global layer of `.pieria/config.toml`).
   */
  static final String DEFAULT_TOML_RESOURCE = "config/pieria-default-config.toml";
  /**
   * Filename of the dumped TOML config; the CLI's ProjectConfigLoader reads it from the config dir.
   */
  static final String TOML_FILE_NAME = "config.toml";

  private final AppDataPathResolver pathResolver;
  private final FirstRunProperties firstRun;
  private final StorageProperties storage;
  private final PieriaProperties pieria;
  private final ModelGateway modelGateway;

  private volatile String lastModelStatus = "not_checked";

  public record SetupState(
    boolean enabled,
    boolean directoriesReady,
    boolean databaseParentReady,
    String modelStatus,
    String modelPullPolicy,
    AppDataPaths paths) {
  }

  public BootstrapService(AppDataPathResolver pathResolver,
                          FirstRunProperties firstRun,
                          StorageProperties storage,
                          PieriaProperties pieria,
                          ModelGateway modelGateway) {
    this.pathResolver = pathResolver;
    this.firstRun = firstRun;
    this.storage = storage;
    this.pieria = pieria;
    this.modelGateway = modelGateway;
  }

  @Override
  public void run(ApplicationArguments args) {
    initialize();
  }

  /**
   * Safe to call repeatedly: directory creation is idempotent and model checks are read-only.
   */
  public SetupState initialize() {
    AppDataPaths paths = pathResolver.resolve();
    if (firstRun.enabled()) {
      for (Path dir : directories(paths)) {
        try {
          Files.createDirectories(dir);
        } catch (IOException e) {
          throw new UncheckedIOException("Cannot create Pieria app-data directory: " + dir, e);
        }
      }
      materializeDefaultConfig(paths.configDir());
    }

    lastModelStatus = modelStatus();
    SetupState state = setupState(lastModelStatus);
    logStartupConfig(state);
    return state;
  }

  /**
   * Writes the bundled default configs to {@code configDir} when they do not yet exist, giving
   * users an editable starting point: {@code pieria.properties} (imported by the daemon on the
   * next start via {@code spring.config.import}) and {@code config.toml} (the global layer of the
   * CLI's project-overridable config). Existing files are never touched, so user edits survive
   * restarts. Best-effort: a write failure is logged, not fatal.
   */
  private void materializeDefaultConfig(Path configDir) {
    materializeTemplate(configDir.resolve(CONFIG_FILE_NAME), DEFAULT_CONFIG_RESOURCE);
    materializeTemplate(configDir.resolve(TOML_FILE_NAME), DEFAULT_TOML_RESOURCE);
  }

  private static void materializeTemplate(Path target, String resource) {
    if (Files.exists(target)) {
      return;
    }
    try (InputStream template = new ClassPathResource(resource).getInputStream()) {
      Files.copy(template, target);
      LOGGER.info("wrote default config to {}", target);
    } catch (IOException e) {
      LOGGER.warn("could not write default config to {}: {}", target, e.getMessage());
    }
  }

  public SetupState setupState() {
    return setupState(firstRun.checkModels() ? lastModelStatus : "skipped");
  }

  private SetupState setupState(String modelStatus) {
    AppDataPaths paths = pathResolver.resolve();
    boolean directoriesReady = directories(paths).stream().allMatch(Files::isDirectory);
    Path databaseParent = paths.databaseFile().getParent();
    boolean databaseParentReady = databaseParent == null || Files.isDirectory(databaseParent);
    return new SetupState(firstRun.enabled(), directoriesReady, databaseParentReady, modelStatus,
      firstRun.modelPullPolicy().name().toLowerCase(java.util.Locale.ROOT), paths);
  }

  private String modelStatus() {
    if (!firstRun.checkModels()) {
      return "skipped";
    }
    try {
      return modelGateway.isModelProviderReachable() ? "provider_reachable" : "provider_unreachable";
    } catch (RuntimeException e) {
      return "unknown";
    }
  }

  private void logStartupConfig(SetupState state) {
    AppDataPaths paths = state.paths();
    PieriaProperties.Model model = pieria.model();
    LOGGER.info(
      """
        pieria startup \
        host={} \
        port={} \
        backend={} \
        databasePath={} \
        configDir={} \
        logsDir={} \
        runtimeDir={} \
        modelProvider={} \
        extractionModel={} \
        synthesisModel={} \
        embedding={} \
        firstRunEnabled={} \
        modelCheck={} \
        modelStatus={} \
        modelPullPolicy={}""",
      pieria.daemon().host(),
      pieria.daemon().port(),
      storage.backend(),
      paths.databaseFile(),
      paths.configDir(),
      paths.logsDir(),
      paths.runtimeDir(),
      pieria.provider().name(),
      model.extractionModel(),
      model.synthesisModel(),
      model.embedding(),
      state.enabled(),
      firstRun.checkModels(),
      state.modelStatus(),
      state.modelPullPolicy());
  }

  private static List<Path> directories(AppDataPaths paths) {
    return List.of(paths.root(), paths.databaseDir(), paths.configDir(), paths.logsDir(), paths.runtimeDir());
  }

}
