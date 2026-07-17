package dev.alvo.pieria.cli.command.daemon;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.client.HealthClient;
import dev.alvo.pieria.client.exception.DaemonInterruptedException;
import java.time.Duration;
import dev.alvo.pieria.cli.modules.daemon.DaemonProcessController;
import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.util.concurrent.Callable;

/**
 * {@code pieria restart} — stop the daemon (if running) and start it again, waiting for it to
 * come healthy. A convenience over {@code stop} + {@code start}; delegates to the same
 * service-aware {@link DaemonProcessController}.
 */
@Command(
  name = "restart",
  description = "Restart the Pieria daemon.",
  mixinStandardHelpOptions = true
)
public final class DaemonRestartCommand implements Callable<Integer> {

  private final Logger log = new Logger();

  @Option(names = "--daemon-url", description = "Daemon base URL (default: $PIERIA_DAEMON_URL or http://127.0.0.1:8077).")
  String daemonUrl;

  @Option(names = "--timeout", description = "Seconds to wait for the daemon to become healthy (default: 15).")
  int timeoutSeconds = 15;

  @Option(names = "--dry-run", description = "Print the stop/start commands without executing them.")
  boolean dryRun;

  private static String host(String url) {
    String host = URI.create(url).getHost();
    return host == null ? "127.0.0.1" : host;
  }

  private static int port(String url) {
    int port = URI.create(url).getPort();
    return port == -1 ? 8077 : port;
  }

  @Override
  public Integer call() {
    String url = DaemonUrls.resolve(daemonUrl);
    DaemonProcessController daemon = new DaemonProcessController();

    daemon.stop(new DaemonProcessController.StopOptions(null, dryRun));
    DaemonProcessController.StartOptions opts = new DaemonProcessController.StartOptions(null, null, host(url), port(url), dryRun);
    DaemonProcessController.StartOutcome outcome = daemon.start(opts);

    if (dryRun) {
      return 0;
    }

    return switch (outcome) {
      case DaemonProcessController.StartedViaService s -> awaitHealthy(url, "Restarted daemon via " + s.detail() + ".");
      case DaemonProcessController.Spawned s -> awaitHealthy(url, "Spawned daemon (pid " + s.pid() + ").");
      case DaemonProcessController.AlreadyRunning ignored -> awaitHealthy(url, "Daemon already running.");
      case DaemonProcessController.NoMechanism n -> {
        log.error(n.guidance());
        yield 3;
      }
      case DaemonProcessController.Failed f -> {
        log.error("Failed to restart daemon: {}", f.detail());
        yield 1;
      }
    };
  }

  private int awaitHealthy(String url, String startedMessage) {
    log.info(startedMessage);
    try {
      if (new HealthClient(url).awaitReachable(Duration.ofSeconds(timeoutSeconds))) {
        log.info("Pieria daemon is up at {}.", url);
        return 0;
      }
    } catch (DaemonInterruptedException ignored) {
      // Preserve the command's existing timeout/failure result after restoring interruption.
    }
    log.error("Daemon did not become healthy within {}s. Check the daemon logs.", timeoutSeconds);
    return 1;
  }
}
