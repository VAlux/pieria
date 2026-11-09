package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "remove", description = "Remove an installed harvester.", mixinStandardHelpOptions = true)
public final class HarvesterRemoveCommand extends AbstractHarvestCommand {

  @Parameters(index = "0", paramLabel = "<id>", description = "Harvester id.")
  String id;

  @Override
  protected int run(HarvestClient client) {
    client.removeHarvester(id);
    log.info("Removed harvester {}.", id);
    return 0;
  }
}
