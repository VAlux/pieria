package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.cli.modules.update.BuildInfo;
import dev.alvo.pieria.client.HarvestClient;
import dev.alvo.pieria.client.TaskClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

@Command(name = "run", description = "Run a source now, even if it is paused.", mixinStandardHelpOptions = true)
public final class SourceRunCommand extends AbstractSourceCommand {

  private static final long POLL_MILLIS = 500;

  @Parameters(index = "0", paramLabel = "<id>", description = "Source id.")
  String id;

  @Option(names = "--dry-run", description = "Count what would change without calling the model or storing anything.")
  boolean dryRun;

  @Option(names = "--wait", description = "Wait for the run to finish.")
  boolean waitForRun;

  @Override
  protected int run(HarvestClient client) throws InterruptedException {
    String taskId = client.run(profile(), id, dryRun).taskId();
    if (!dryRun && !waitForRun) {
      log.info("Started task {}. Follow it with 'pieria task list'.", taskId);
      return 0;
    }
    TaskClient tasks = new TaskClient(resolvedDaemonUrl(), BuildInfo.clientIdentity());
    TaskStatusResponse status = tasks.status(taskId);
    while (status.status().equals("RUNNING")) {
      Thread.sleep(POLL_MILLIS);
      status = tasks.status(taskId);
    }
    return report(status);
  }

  private int report(TaskStatusResponse status) {
    if (!status.status().equals("SUCCEEDED")) {
      log.error("The run did not finish: {}", status.errorMessage() == null ? status.status() : status.errorMessage());
      return 1;
    }
    JsonNode result = status.result();
    log.info("{} items reported, {} changed, {} characters", result.path("itemsSeen").asLong(), result.path("itemsChanged").asLong(),
      result.path("chars").asLong());
    log.info("About {} chunks to extract.", result.path("estimatedChunks").asLong());
    if (!result.path("status").asString("").equals("succeeded")) {
      log.error("The run failed. See 'pieria source runs {}'.", id);
      return 1;
    }
    return 0;
  }
}
