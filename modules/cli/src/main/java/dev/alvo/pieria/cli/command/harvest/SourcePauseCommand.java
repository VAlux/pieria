package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "pause", description = "Stop running a source on schedule.", mixinStandardHelpOptions = true)
public final class SourcePauseCommand extends AbstractSourceCommand {

  @Parameters(index = "0", paramLabel = "<id>", description = "Source id.")
  String id;

  @Override
  protected int run(HarvestClient client) {
    client.pause(profile(), id);
    log.info("Paused source {}.", id);
    return 0;
  }
}
