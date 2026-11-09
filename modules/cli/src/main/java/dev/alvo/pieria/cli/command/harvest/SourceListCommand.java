package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.api.response.SourceResponse;
import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;

import java.util.List;

@Command(name = "list", description = "List a profile's sources.", mixinStandardHelpOptions = true)
public final class SourceListCommand extends AbstractSourceCommand {

  @Override
  protected int run(HarvestClient client) {
    List<SourceResponse> sources = client.list(profile()).sources();
    if (sources.isEmpty()) {
      log.info("No sources.");
      return 0;
    }
    for (SourceResponse source : sources) {
      log.info("{}  {}  {}{}  last run: {}", source.id(), source.harvester(), source.status(), source.running() ? " (running)" : "",
        source.lastRun() == null ? "never" : source.lastRun().status());
    }
    return 0;
  }
}
