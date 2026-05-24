package dev.alvo.pieria.config;

import dev.alvo.pieria.config.AppDataPathResolver.AppDataPaths;
import dev.alvo.pieria.model.ModelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Idempotent local first-run setup. It creates the app-data directory set, reports model-provider
 * reachability when configured, and logs a sanitized startup summary for local operations.
 */
@Service
public class FirstRunService implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(FirstRunService.class);

  private final AppDataPathResolver pathResolver;
  private final FirstRunProperties firstRun;
  private final StorageProperties storage;
  private final PieriaProperties pieria;
  private final ModelGateway modelGateway;
  private volatile String lastModelStatus = "not_checked";

  public FirstRunService(AppDataPathResolver pathResolver,
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
    }

    lastModelStatus = modelStatus();
    SetupState state = setupState(lastModelStatus);
    logStartupConfig(state);
    return state;
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
    log.info(
      "pieria startup host={} port={} backend={} databasePath={} configDir={} logsDir={} runtimeDir={} "
        + "modelProvider=ollama chatSmall={} chatLarge={} embedding={} firstRunEnabled={} "
        + "modelCheck={} modelStatus={} modelPullPolicy={}",
      pieria.daemon().host(), pieria.daemon().port(), storage.backend(), paths.databaseFile(),
      paths.configDir(), paths.logsDir(), paths.runtimeDir(),
      model.chatSmall(), model.chatLarge(), model.embedding(),
      state.enabled(), firstRun.checkModels(), state.modelStatus(), state.modelPullPolicy());
  }

  private static List<Path> directories(AppDataPaths paths) {
    return List.of(paths.root(), paths.databaseDir(), paths.configDir(), paths.logsDir(), paths.runtimeDir());
  }

  public record SetupState(boolean enabled,
                           boolean directoriesReady,
                           boolean databaseParentReady,
                           String modelStatus,
                           String modelPullPolicy,
                           AppDataPaths paths) {
  }
}
