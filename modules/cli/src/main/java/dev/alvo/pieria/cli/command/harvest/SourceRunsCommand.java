package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.api.response.SourceRunResponse;
import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;

@Command(name = "runs", description = "List a source's recent runs.", mixinStandardHelpOptions = true)
public final class SourceRunsCommand extends AbstractSourceCommand {

  @Parameters(index = "0", paramLabel = "<id>", description = "Source id.")
  String id;

  @Option(names = "--limit", description = "How many runs to show (default: 10).")
  int limit = 10;

  @Override
  protected int run(HarvestClient client) {
    List<SourceRunResponse> runs = client.runs(profile(), id, limit).runs();
    if (runs.isEmpty()) {
      log.info("No runs yet.");
      return 0;
    }
    for (SourceRunResponse run : runs) {
      log.info("{}  {}{}  {} items, {} changed, {} deleted, {} memories{}", run.startedAt(), run.status(), run.dryRun() ? " (dry run)" : "",
        run.itemsSeen(), run.itemsChanged(), run.itemsDeleted(), run.memoriesStored(),
        run.errorMessage() == null ? "" : "  " + run.errorMessage());
    }
    return 0;
  }
}
