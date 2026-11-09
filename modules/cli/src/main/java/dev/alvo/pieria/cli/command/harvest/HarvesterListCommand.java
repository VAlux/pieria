package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.api.response.HarvesterResponse;
import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;

import java.util.List;

@Command(name = "list", description = "List installed harvesters.", mixinStandardHelpOptions = true)
public final class HarvesterListCommand extends AbstractHarvestCommand {

  @Override
  protected int run(HarvestClient client) {
    List<HarvesterResponse> harvesters = client.harvesters().harvesters();
    if (harvesters.isEmpty()) {
      log.info("No harvesters installed.");
      return 0;
    }
    for (HarvesterResponse harvester : harvesters) {
      log.info("{}  {} {}{}", harvester.id(), harvester.name(), harvester.version(), Boolean.TRUE.equals(harvester.bundled()) ? " (bundled)" : "");
    }
    return 0;
  }
}
