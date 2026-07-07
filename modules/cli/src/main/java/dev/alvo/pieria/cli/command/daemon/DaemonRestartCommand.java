package dev.alvo.pieria.cli.command.daemon;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.cli.modules.daemon.DaemonClient;
import dev.alvo.pieria.cli.modules.daemon.DaemonProcess;
import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.util.concurrent.Callable;

/**
 * {@code pieria restart} — stop the daemon (if running) and start it again, waiting for it to
 * come healthy. A convenience over {@code stop} + {@code start}; delegates to the same
 * service-aware {@link DaemonProcess}.
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
    DaemonProcess daemon = new DaemonProcess();

    daemon.stop(new DaemonProcess.StopOptions(null, dryRun));
    DaemonProcess.StartOptions opts = new DaemonProcess.StartOptions(null, null, host(url), port(url), dryRun);
    DaemonProcess.StartOutcome outcome = daemon.start(opts);

    if (dryRun) {
      return 0;
    }

    return switch (outcome) {
      case DaemonProcess.StartedViaService s -> awaitHealthy(url, "Restarted daemon via " + s.detail() + ".");
      case DaemonProcess.Spawned s -> awaitHealthy(url, "Spawned daemon (pid " + s.pid() + ").");
      case DaemonProcess.AlreadyRunning ignored -> awaitHealthy(url, "Daemon already running.");
      case DaemonProcess.NoMechanism n -> {
        log.error(n.guidance());
        yield 3;
      }
      case DaemonProcess.Failed f -> {
        log.error("Failed to restart daemon: {}", f.detail());
        yield 1;
      }
    };
  }

  private int awaitHealthy(String url, String startedMessage) {
    log.info(startedMessage);
    if (new DaemonClient(url).awaitHealthy(timeoutSeconds)) {
      log.info("Pieria daemon is up at {}.", url);
      return 0;
    }
    log.error("Daemon did not become healthy within {}s. Check the daemon logs.", timeoutSeconds);
    return 1;
  }
}
