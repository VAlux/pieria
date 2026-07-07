package dev.alvo.pieria.cli.command.daemon;

import dev.alvo.pieria.api.response.HealthResponse;
import dev.alvo.pieria.api.response.StatusResponse;
import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.client.exception.DaemonUnavailableException;
import dev.alvo.pieria.client.HealthClient;
import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code pieria status} — report whether the daemon is running and, when it is, a rich
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

  private final Logger log = new Logger();

  private void line(String label, String value) {
    log.info(String.format("  %-22s %s", label + ":", value == null ? "—" : value));
  }

  @Override
  public Integer call() {
    String url = DaemonUrls.resolve(daemonUrl);
    try {
      HealthClient.HealthStatusSnapshot snapshot = new HealthClient(url).snapshot();
      if (snapshot.healthy()) {
        log.info("Pieria daemon: UP ({})", url);
        printReport(snapshot.health(), snapshot.status());
        return 0;
      }
      log.info("Pieria daemon: DEGRADED ({})", url);
      printReport(snapshot.health(), snapshot.status());
      return 5;
    } catch (DaemonUnavailableException e) {
      log.error("Pieria daemon is not reachable at {}.", url);
      log.error("Start it with 'pieria start'.");
      return 3;
    }
  }

  private void printReport(HealthResponse health, StatusResponse status) {
    if (health != null) {
      line("Health", health.status());
      line("Database", health.db());
      line("Model provider", health.modelProvider());
    }
    if (status == null) {
      log.info("  (status detail unavailable)");
      return;
    }
    line("Setup", status.status());
    line("Backend", status.backend());
    line("Provider", status.modelProvider());
    line("Extraction model", status.extractionModel());
    line("Synthesis model", status.synthesisModel());
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
