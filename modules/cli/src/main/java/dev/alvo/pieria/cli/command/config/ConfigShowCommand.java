package dev.alvo.pieria.cli.command.config;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.cli.modules.config.ConfigClient;
import dev.alvo.pieria.cli.modules.config.ConfigClient.DaemonDown;
import dev.alvo.pieria.cli.modules.config.ConfigClient.Failure;
import dev.alvo.pieria.cli.modules.config.ConfigClient.Success;
import dev.alvo.pieria.cli.modules.config.HttpConfigClient;
import dev.alvo.pieria.cli.modules.init.IngestClient;
import dev.alvo.pieria.mapping.ProfileResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code pieria config show} — print the profile's effective configuration as the daemon
 * resolves it (global properties overlaid with any synced per-profile overrides).
 */
@Command(
  name = "show",
  description = "Show the daemon's effective config for the project's profile.",
  mixinStandardHelpOptions = true
)
public final class ConfigShowCommand implements Callable<Integer> {

  private static final String DEFAULT_DAEMON_URL = "http://127.0.0.1:8077";

  private final Logger log = new Logger();

  @Option(names = "--project-dir", description = "Project directory (default: current directory).")
  public Path projectDir = Path.of("");

  @Option(names = "--profile", description = "Explicit profile slug; omit to auto-derive per directory.")
  String profile;

  @Option(names = "--daemon-url", description = "Daemon base URL (default: $PIERIA_DAEMON_URL or http://127.0.0.1:8077).")
  String daemonUrl;

  @Override
  public Integer call() {
    Path dir = projectDir.toAbsolutePath().normalize();
    String resolvedProfile = resolveProfile(dir);
    String url = resolveDaemonUrl();

    ConfigClient client = new HttpConfigClient(url);
    if (client.ping() == IngestClient.Reachability.DAEMON_DOWN) {
      return daemonDown(url);
    }

    return switch (client.get(resolvedProfile)) {
      case Success success -> {
        log.info("Effective config for profile '{}':", resolvedProfile);
        log.info("{}", success.body());
        yield 0;
      }
      case DaemonDown ignored -> daemonDown(url);
      case Failure failure -> {
        log.error("Config fetch failed (HTTP {}): {}", failure.status(), failure.body());
        yield 1;
      }
    };
  }

  private int daemonDown(String url) {
    log.error("Pieria daemon is not reachable at {}.", url);
    log.error("Start it with 'pieria daemon start' and re-run 'pieria config show'.");
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
