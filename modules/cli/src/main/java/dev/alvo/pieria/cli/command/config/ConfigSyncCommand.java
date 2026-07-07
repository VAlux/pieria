package dev.alvo.pieria.cli.command.config;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.cli.modules.config.ConfigClient;
import dev.alvo.pieria.cli.modules.config.HttpConfigClient;
import dev.alvo.pieria.cli.modules.config.ProjectConfigLoader;
import dev.alvo.pieria.cli.modules.init.Reachability;
import dev.alvo.pieria.config.model.PieriaConfigFile;
import dev.alvo.pieria.config.toml.ConfigCodec;
import dev.alvo.pieria.mapping.ProfileResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code pieria config sync} — push the merged (project &gt; global) daemon-overridable config to
 * the project's profile. Authoritative: when the merged layers set no {@code [pieria]} overrides,
 * the push clears any previously stored ones, so the daemon always mirrors the config files.
 */
@Command(
  name = "sync",
  description = "Push the merged project config overrides to the daemon profile.",
  mixinStandardHelpOptions = true
)
public final class ConfigSyncCommand implements Callable<Integer> {

  private static final String DEFAULT_DAEMON_URL = "http://127.0.0.1:8077";

  private final Logger log = new Logger();

  @Option(names = "--project-dir", description = "Project directory (default: current directory).")
  public Path projectDir = Path.of("");
  @Option(names = "--profile", description = "Explicit profile slug; omit to auto-derive per directory.")
  String profile;
  @Option(names = "--daemon-url", description = "Daemon base URL (default: $PIERIA_DAEMON_URL or http://127.0.0.1:8077).")
  String daemonUrl;
  @Option(names = "--dry-run", description = "Print the overrides that would be pushed, without contacting the daemon.")
  boolean dryRun;
  @Option(names = "--config-dir", description = "Directory holding the global config.toml (default: $PIERIA_CONFIG_DIR or the OS config dir).")
  Path configDir;

  @Override
  public Integer call() {
    Path dir = projectDir.toAbsolutePath().normalize();
    String resolvedProfile = resolveProfile(dir);
    String url = resolveDaemonUrl();
    ProjectConfigLoader loader = ProjectConfigLoader.create(dir, configDir);

    PieriaConfigFile config;
    try {
      config = loader.load();
    } catch (Exception e) {
      log.error("Failed to load config ({} / {}): {}",
        loader.globalConfigFile(), loader.projectConfigFile(), e.getMessage());
      return 2;
    }

    String overridesJson = ConfigCodec.toJson(config.pieria());
    if (dryRun) {
      if (config.pieria().isEmpty()) {
        log.info("No [pieria] overrides in {} or {} — would clear profile '{}' overrides.",
          loader.globalConfigFile(), loader.projectConfigFile(), resolvedProfile);
      } else {
        log.info("Would push to profile '{}' at {}: {}", resolvedProfile, url, overridesJson);
      }
      return 0;
    }

    ConfigClient client = new HttpConfigClient(url);
    if (client.ping() == Reachability.DAEMON_DOWN) {
      return daemonDown(url);
    }

    return switch (client.put(resolvedProfile, overridesJson)) {
      case ConfigClient.Success s -> {
        log.info("Synced config overrides to profile '{}'. Effective config:", resolvedProfile);
        log.info("{}", s.body());
        yield 0;
      }
      case ConfigClient.DaemonDown ignored -> daemonDown(url);
      case ConfigClient.Failure f -> {
        log.error("Config sync failed (HTTP {}): {}", f.status(), f.body());
        yield 1;
      }
    };
  }

  private int daemonDown(String url) {
    log.error("Pieria daemon is not reachable at {}.", url);
    log.error("Start it with 'pieria start' and re-run 'pieria config sync'.");
    return 3;
  }

  private String resolveProfile(Path dir) {
    if (profile != null && !profile.isBlank()) {
      return ProfileResolver.normalize(profile);
    }
    return ProfileResolver.create(dir).resolve();
  }

  private String resolveDaemonUrl() {
    if (daemonUrl != null && !daemonUrl.isBlank()) {
      return daemonUrl;
    }
    return System.getenv().getOrDefault("PIERIA_DAEMON_URL", DEFAULT_DAEMON_URL);
  }
}
