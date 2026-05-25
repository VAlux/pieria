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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    logModelPullGuidance(state);
    printStartupSummary(state);
    return state;
  }

  /**
   * Compute the LOG-ONLY {@code ollama pull <model>} commands the user should run, given the
   * configured models and the set the provider currently has available. NEVER triggers a download.
   * Returns an empty list when the policy is NEVER, when model checks are disabled, or when the
   * provider is unreachable. Exposed (package-private) so policy behavior is unit-testable without
   * touching stdout/logging.
   *
   * @param availableModels the model names the provider reports as locally available
   */
  List<String> pullCommands(Set<String> availableModels) {
    FirstRunProperties.ModelPullPolicy policy = firstRun.modelPullPolicy();
    if (policy == FirstRunProperties.ModelPullPolicy.NEVER) {
      return List.of();
    }
    if (!firstRun.checkModels()) {
      return List.of();
    }
    Set<String> available = availableModels == null ? Set.of() : availableModels;

    // Configured models, de-duplicated and order-preserving (small, large, embedding).
    PieriaProperties.Model model = pieria.model();
    LinkedHashSet<String> configured = new LinkedHashSet<>();
    addIfPresent(configured, model.chatSmall());
    addIfPresent(configured, model.chatLarge());
    addIfPresent(configured, model.embedding());

    List<String> commands = new ArrayList<>();
    for (String name : configured) {
      boolean pull = policy == FirstRunProperties.ModelPullPolicy.ALWAYS || !available.contains(name);
      if (pull) {
        commands.add("ollama pull " + name);
      }
    }
    return commands;
  }

  private static void addIfPresent(Set<String> target, String value) {
    if (value != null && !value.isBlank()) {
      target.add(value.strip());
    }
  }

  /**
   * Log (never execute) the {@code ollama pull} commands the user should run. Guarded so it is a
   * no-op when model checks are off, the policy is NEVER, or the provider is unreachable.
   */
  private void logModelPullGuidance(SetupState state) {
    if (firstRun.modelPullPolicy() == FirstRunProperties.ModelPullPolicy.NEVER
      || !firstRun.checkModels()
      || !"provider_reachable".equals(state.modelStatus())) {
      return;
    }
    Set<String> available;
    try {
      available = modelGateway.availableModels();
    } catch (RuntimeException e) {
      available = Set.of();
    }
    List<String> commands = pullCommands(available);
    if (commands.isEmpty()) {
      log.info("model pull policy={} - all configured models are available; nothing to pull",
        state.modelPullPolicy());
      return;
    }
    log.info("model pull policy={} - Pieria never downloads models automatically. Run the following "
      + "command(s) to make the configured models available:", state.modelPullPolicy());
    for (String command : commands) {
      log.info("  {}", command);
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
    log.info(
      "pieria startup host={} port={} backend={} databasePath={} configDir={} logsDir={} runtimeDir={} "
        + "modelProvider=ollama chatSmall={} chatLarge={} embedding={} firstRunEnabled={} "
        + "modelCheck={} modelStatus={} modelPullPolicy={}",
      pieria.daemon().host(), pieria.daemon().port(), storage.backend(), paths.databaseFile(),
      paths.configDir(), paths.logsDir(), paths.runtimeDir(),
      model.chatSmall(), model.chatLarge(), model.embedding(),
      state.enabled(), firstRun.checkModels(), state.modelStatus(), state.modelPullPolicy());
  }

  /** Print the human-readable startup summary to STDOUT. Idempotent and safe to repeat. */
  private void printStartupSummary(SetupState state) {
    System.out.print(buildStartupSummary(state));
  }

  /**
   * Build the human-readable first-run summary printed to STDOUT: the local daemon URL, a one-line
   * description of how profiles are resolved, and an MCP setup snippet referencing the gateway. Kept
   * free of secrets and provider URLs. Exposed (package-private) so the content is unit-testable
   * without capturing stdout. Idempotent: building it has no side effects.
   */
  String buildStartupSummary(SetupState state) {
    String url = "http://" + pieria.daemon().host() + ":" + pieria.daemon().port();
    String gateway = resolveGatewayPath();
    String snippet = """
      {
        "mcpServers": {
          "pieria": {
            "command": "%s",
            "env": {
              "PIERIA_DAEMON_URL": "%s"
            }
          }
        }
      }""".formatted(gateway, url);

    StringBuilder sb = new StringBuilder();
    sb.append(System.lineSeparator());
    sb.append("=== Pieria is ready ===").append(System.lineSeparator());
    sb.append("Daemon URL: ").append(url).append(System.lineSeparator());
    sb.append("Profiles: each working directory maps to one memory profile, resolved from "
        + "$PIERIA_PROFILE, else the git remote repo name, else the directory basename "
        + "(normalized to a lowercase slug; empty -> \"default\").")
      .append(System.lineSeparator());
    sb.append("Harness setup: run 'pieria harness install claude-code' (or 'codex') from your "
        + "project to register the MCP gateway and lifecycle hooks automatically.")
      .append(System.lineSeparator());
    sb.append("Manual MCP config (equivalent), if you prefer to wire it by hand:")
      .append(System.lineSeparator());
    sb.append(snippet).append(System.lineSeparator());
    sb.append("=======================").append(System.lineSeparator());
    return sb.toString();
  }

  /**
   * Resolve the gateway command path for the MCP snippet. Prefers an explicit {@code PIERIA_HOME}
   * (matching the {@code @PIERIA_HOME@/bin/pieria-gateway} packaging template); otherwise falls back to
   * the bare {@code pieria-gateway} executable name (expected on PATH).
   */
  private static String resolveGatewayPath() {
    String home = System.getenv("PIERIA_HOME");
    if (home != null && !home.isBlank()) {
      return home.strip().replaceAll("/+$", "") + "/bin/pieria-gateway";
    }
    return "pieria-gateway";
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
