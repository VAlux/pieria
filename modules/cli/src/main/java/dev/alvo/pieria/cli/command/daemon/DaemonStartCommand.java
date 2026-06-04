package dev.alvo.pieria.cli.command.daemon;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.cli.modules.daemon.DaemonClient;
import dev.alvo.pieria.cli.modules.daemon.DaemonProcess;
import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
import dev.alvo.pieria.cli.modules.daemon.StartupSummary;
import dev.alvo.pieria.cli.modules.harness.PathResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.util.concurrent.Callable;

/**
 * {@code pieria daemon start} — start the daemon if it is not already running.
 *
 * <p>Hybrid: if an OS service is installed (launchd / systemd --user) the start is delegated to it;
 * otherwise a detached daemon process is spawned and tracked by a PID file. After triggering a
 * start, it polls {@code /pieria-health} until the daemon answers or the wait budget elapses.
 */
@Command(
  name = "start",
  description = "Start the Pieria daemon if it is down.",
  mixinStandardHelpOptions = true
)
public final class DaemonStartCommand implements Callable<Integer> {

  @Option(names = "--daemon-url", description = "Daemon base URL (default: $PIERIA_DAEMON_URL or http://127.0.0.1:8077).")
  String daemonUrl;

  @Option(names = "--daemon", description = "Daemon executable or jar to spawn (default: $PIERIA_DAEMON_BIN, PATH, or ~/.local/bin/pieria-daemon).")
  String daemonBinary;

  @Option(names = "--runtime-dir", description = "Runtime directory for the PID file (default: OS app-data run dir).")
  String runtimeDir;

  @Option(names = "--timeout", description = "Seconds to wait for the daemon to become healthy (default: 15).")
  int timeoutSeconds = 15;

  @Option(names = "--dry-run", description = "Print the start command without executing it.")
  boolean dryRun;

  private final Logger log = new Logger();

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
    DaemonClient client = new DaemonClient(url);

    if (!dryRun && client.ping() == DaemonClient.Reachability.OK) {
      log.info("Pieria daemon is already running at {}.", url);
      return 0;
    }

    DaemonProcess process = new DaemonProcess();
    DaemonProcess.StartOptions opts =
      new DaemonProcess.StartOptions(daemonBinary, runtimeDir, host(url), port(url), dryRun);

    return switch (process.start(opts)) {
      case DaemonProcess.AlreadyRunning ignored -> {
        log.info("Pieria daemon is already running at {}.", url);
        yield 0;
      }
      case DaemonProcess.StartedViaService s -> {
        log.info("Started Pieria daemon via {}.", s.detail());
        yield dryRun ? 0 : awaitHealthy(client, url);
      }
      case DaemonProcess.Spawned s -> {
        if (dryRun) {
          yield 0;
        }
        log.info("Spawned Pieria daemon (pid {}).", s.pid());
        yield awaitHealthy(client, url);
      }
      case DaemonProcess.NoMechanism n -> {
        log.error(n.guidance());
        yield 3;
      }
      case DaemonProcess.Failed f -> {
        log.error("Failed to start daemon: {}", f.detail());
        yield 1;
      }
    };
  }

  /**
   * Poll {@code /pieria-health} until the daemon answers or the timeout elapses.
   */
  private int awaitHealthy(DaemonClient client, String url) {
    long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (client.ping() == DaemonClient.Reachability.OK) {
        log.info("Pieria daemon is up at {}.", url);
        log.print(StartupSummary.render(url, PathResolver.create().gatewayCommand()));
        return 0;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    log.error("Daemon did not become healthy within {}s. Check the daemon logs.", timeoutSeconds);
    return 1;
  }
}
