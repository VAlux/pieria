package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.api.response.SourceResponse;
import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "show", description = "Show a source's settings and state.", mixinStandardHelpOptions = true)
public final class SourceShowCommand extends AbstractSourceCommand {

  @Parameters(index = "0", paramLabel = "<id>", description = "Source id.")
  String id;

  @Override
  protected int run(HarvestClient client) {
    SourceResponse source = client.get(profile(), id);
    log.info("{} ({})", source.id(), source.harvester());
    log.info("  status:   {}{}", source.status(), source.running() ? " (running)" : "");
    log.info("  mode:     {}", source.mode());
    log.info("  interval: {} seconds", source.intervalSeconds());
    log.info("  next run: {}", source.nextRunAt());
    log.info("  config:   {}", source.config());
    log.info("  secrets:  {}", source.secretNames().isEmpty() ? "(none)" : String.join(", ", source.secretNames()));
    if (source.maxItemsPerRun() != null) {
      log.info("  max items per run: {}", source.maxItemsPerRun());
    }
    if (source.maxCharsPerRun() != null) {
      log.info("  max chars per run: {}", source.maxCharsPerRun());
    }
    if (source.lastRun() != null) {
      log.info("  last run: {}{}", source.lastRun().status(),
        source.lastRun().errorMessage() == null ? "" : ": " + source.lastRun().errorMessage());
    }
    if (source.sweepBlockedCount() != null) {
      log.info("  A sweep would delete {} items and was held back. If that's expected, run 'pieria source accept-sweep {}'.",
        source.sweepBlockedCount(), id);
    }
    return 0;
  }
}
