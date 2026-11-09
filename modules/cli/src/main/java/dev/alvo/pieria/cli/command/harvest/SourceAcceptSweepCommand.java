package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "accept-sweep", description = "Let the next sweep delete what the mass-delete guard held back.", mixinStandardHelpOptions = true)
public final class SourceAcceptSweepCommand extends AbstractSourceCommand {

  @Parameters(index = "0", paramLabel = "<id>", description = "Source id.")
  String id;

  @Override
  protected int run(HarvestClient client) {
    client.acceptSweep(profile(), id);
    log.info("The next complete sweep of source {} may delete what was held back.", id);
    return 0;
  }
}
