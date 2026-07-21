package dev.alvo.pieria.cli.command.init;

import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.cli.log.ProgressReporter;
import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
import dev.alvo.pieria.cli.modules.task.TaskPoller;
import dev.alvo.pieria.cli.modules.update.BuildInfo;
import dev.alvo.pieria.client.HealthClient;
import dev.alvo.pieria.client.ReminiscenceClient;
import dev.alvo.pieria.client.TaskClient;
import dev.alvo.pieria.client.exception.DaemonClientException;
import dev.alvo.pieria.client.exception.DaemonHttpException;
import dev.alvo.pieria.client.exception.DaemonUnavailableException;
import dev.alvo.pieria.mapping.ProfileResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code pieria reminisce} — weave "orphan" memories (those stored without a graph fragment, e.g. via
 * {@code remember}) into the profile's entity-relation graph by retroactively running the ingest
 * graph-extraction over their content.
 *
 * <p>The adoption runs as a background task on the daemon (model-heavy). {@code --dry-run} instead
 * asks the daemon how many orphans a run would process, using a plain count query (no model call).
 */
@Command(
  name = "reminisce",
  description = "Adopt orphan memories into the profile's knowledge graph",
  mixinStandardHelpOptions = true
)
public final class ReminisceCommand implements Callable<Integer> {

  private final Logger log = new Logger();

  @Option(names = "--project-dir", description = "Project directory used to auto-derive the profile (default: current directory).")
  Path projectDir = Path.of("");

  @Option(names = "--profile", description = "Explicit profile slug; omit to auto-derive per directory.")
  String profile;

  @Option(names = "--daemon-url", description = "Daemon base URL (default: $PIERIA_DAEMON_URL or http://127.0.0.1:8077).")
  String daemonUrl;

  @Option(names = "--dry-run", description = "Report how many orphan memories a run would adopt, without extracting anything.")
  boolean dryRun;

  @Override
  public Integer call() {
    Path dir = projectDir.toAbsolutePath().normalize();
    String resolvedProfile = resolveProfile(dir);
    String url = DaemonUrls.resolve(daemonUrl);

    if (!new HealthClient(url, BuildInfo.clientIdentity()).reachable()) {
      return daemonDown(url);
    }

    if (dryRun) {
      try {
        long orphans = new ReminiscenceClient(url, BuildInfo.clientIdentity()).orphanCount(resolvedProfile);
        log.info("Profile '{}' has {} orphan memor{} that a run would adopt.",
          resolvedProfile, orphans, orphans == 1 ? "y" : "ies");
        return 0;
      } catch (DaemonUnavailableException e) {
        return daemonDown(url);
      } catch (DaemonClientException e) {
        log.error("Could not read orphan count for '{}': {}", resolvedProfile, e.getMessage());
        return 1;
      }
    }

    return run(url, resolvedProfile);
  }

  /** Submit the adoption task, poll it, and map the outcome to an exit code. */
  private int run(String url, String profile) {
    log.info("Adopting orphan memories into the graph for profile '{}'…", profile);
    ProgressReporter reporter = new ProgressReporter();
    try {
      String taskId = new ReminiscenceClient(url, BuildInfo.clientIdentity()).submit(profile, "reminisce").taskId();
      TaskStatusResponse task = new TaskPoller(new TaskClient(url, BuildInfo.clientIdentity())).await(taskId, reporter);
      reporter.finish();
      if ("SUCCEEDED".equals(task.status())) {
        report(task.result());
        return 0;
      }
      if ("model-unavailable".equals(task.errorKind())) {
        log.error("The daemon is up but the model provider is unreachable, so nothing was adopted.");
        if (task.errorMessage() != null && !task.errorMessage().isBlank()) {
          log.error("Reason: {}", task.errorMessage());
        } else {
          log.error("Start your model provider (e.g. Ollama) and re-run 'pieria reminisce'.");
        }
        return 4;
      }
      log.error("Reminisce failed (HTTP -1): {}",
        task.errorMessage() == null ? "reminisce task failed" : task.errorMessage());
      return 1;
    } catch (DaemonUnavailableException e) {
      reporter.finish();
      return daemonDown(url);
    } catch (DaemonHttpException e) {
      reporter.finish();
      if (e.status() == 503) {
        log.error("The daemon is up but the model provider is unreachable, so nothing was adopted.");
        if (e.daemonMessage() != null && !e.daemonMessage().isBlank()) {
          log.error("Reason: {}", e.daemonMessage());
        }
        return 4;
      }
      log.error("Reminisce failed (HTTP {}): {}", e.status(), e.body());
      return 1;
    } catch (DaemonClientException e) {
      reporter.finish();
      log.error("Reminisce failed (HTTP -1): {}", e.getMessage());
      return 1;
    }
  }

  /** Terminal "done" line from the {@code ReminiscenceResult} JSON. */
  private void report(JsonNode result) {
    int scanned = integer(result, "memoriesScanned");
    int adopted = integer(result, "memoriesAdopted");
    int entities = integer(result, "entitiesAdded");
    int edges = integer(result, "edgesAdded");
    if (scanned == 0) {
      log.info("Done. No orphan memories to adopt.");
      return;
    }
    log.info("Done. Scanned {} orphan(s), adopted {} into the graph ({} entities, {} edges).",
      scanned, adopted, entities, edges);
  }

  private static int integer(JsonNode node, String field) {
    if (node == null) {
      return 0;
    }
    var value = node.get(field);
    return value == null || value.isNull() ? 0 : value.asInt(0);
  }

  private int daemonDown(String url) {
    log.error("Pieria daemon is not reachable at {}.", url);
    log.error("Start it with 'pieria start' and re-run 'pieria reminisce'.");
    return 3;
  }

  private String resolveProfile(Path dir) {
    if (profile != null && !profile.isBlank()) {
      return ProfileResolver.normalize(profile);
    }
    return ProfileResolver.create(dir).resolve();
  }
}
