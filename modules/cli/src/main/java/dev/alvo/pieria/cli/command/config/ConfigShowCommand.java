package dev.alvo.pieria.cli.command.config;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
import dev.alvo.pieria.cli.modules.update.BuildInfo;
import dev.alvo.pieria.client.ConfigClient;
import dev.alvo.pieria.client.HealthClient;
import dev.alvo.pieria.client.exception.DaemonHttpException;
import dev.alvo.pieria.client.exception.DaemonUnavailableException;
import dev.alvo.pieria.config.toml.ConfigCodec;
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

    if (!new HealthClient(url, BuildInfo.clientIdentity()).reachable()) {
      return daemonDown(url);
    }
    try {
      var effective = new ConfigClient(url, BuildInfo.clientIdentity()).get(resolvedProfile);
      log.info("Effective config for profile '{}':", resolvedProfile);
      log.info("{}", ConfigCodec.toJson(effective));
      return 0;
    } catch (DaemonUnavailableException e) {
      return daemonDown(url);
    } catch (DaemonHttpException e) {
      log.error("Config fetch failed (HTTP {}): {}", e.status(), e.body());
      return 1;
    }
  }

  private int daemonDown(String url) {
    log.error("Pieria daemon is not reachable at {}.", url);
    log.error("Start it with 'pieria start' and re-run 'pieria config show'.");
    return 3;
  }

  private String resolveProfile(Path dir) {
    if (profile != null && !profile.isBlank()) {
      return ProfileResolver.normalize(profile);
    }
    return ProfileResolver.create(dir).resolve();
  }

  private String resolveDaemonUrl() {
    return DaemonUrls.resolve(daemonUrl);
  }
}
