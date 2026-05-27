package dev.alvo.pieria.cli.command.daemon;

import dev.alvo.pieria.api.response.HealthResponse;
import dev.alvo.pieria.api.response.StatusResponse;
import dev.alvo.pieria.cli.modules.daemon.DaemonClient;
import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code pieria daemon status} — report whether the daemon is running and, when it is, a rich
 * operational snapshot drawn from {@code /pieria-health} (overall health, db, model provider) and
 * {@code /pieria-status} (backend, models, paths, vectorization backlog).
 *
 * <p>Exit codes: {@code 0} up, {@code 5} reachable-but-degraded, {@code 3} unreachable — so scripts
 * can distinguish the three states.
 */
@Command(
  name = "status",
  description = "Report daemon health, configuration, and paths.",
  mixinStandardHelpOptions = true
)
public final class DaemonStatusCommand implements Callable<Integer> {

  @Option(names = "--daemon-url", description = "Daemon base URL (default: $PIERIA_DAEMON_URL or http://127.0.0.1:8077).")
  String daemonUrl;

  private static void line(String label, String value) {
    System.out.printf("  %-22s %s%n", label + ":", value == null ? "—" : value);
  }

  @Override
  public Integer call() {
    String url = DaemonUrls.resolve(daemonUrl);
    DaemonClient client = new DaemonClient(url);

    return switch (client.status()) {
      case DaemonClient.Reachable r -> {
        System.out.printf("Pieria daemon: UP (%s)%n", url);
        printReport(r.health(), r.status());
        yield 0;
      }
      case DaemonClient.Degraded d -> {
        System.out.printf("Pieria daemon: DEGRADED (%s)%n", url);
        printReport(d.health(), d.status());
        yield 5;
      }
      case DaemonClient.Down ignored -> {
        System.err.printf("Pieria daemon is not reachable at %s.%n", url);
        System.err.println("Start it with 'pieria daemon start'.");
        yield 3;
      }
    };
  }

  private void printReport(HealthResponse health, StatusResponse status) {
    if (health != null) {
      line("Health", health.status());
      line("Database", health.db());
      line("Model provider", health.modelProvider());
    }
    if (status == null) {
      System.out.println("  (status detail unavailable)");
      return;
    }
    line("Setup", status.status());
    line("Backend", status.backend());
    line("Provider", status.modelProvider());
    line("Chat (small)", status.chatSmallModel());
    line("Chat (large)", status.chatLargeModel());
    line("Embedding", status.embeddingModel());
    line("Database path", status.databasePath());
    line("Vectorization backlog",
      status.vectorizationOutboxDepth() == null ? "unknown" : status.vectorizationOutboxDepth().toString());

    StatusResponse.Setup setup = status.setup();
    if (setup != null) {
      line("Directories ready", Boolean.toString(setup.directoriesReady()));
      line("Model status", setup.modelStatus());
      line("Config dir", setup.configDir());
      line("Logs dir", setup.logsDir());
      line("Runtime dir", setup.runtimeDir());
    }
  }
}
