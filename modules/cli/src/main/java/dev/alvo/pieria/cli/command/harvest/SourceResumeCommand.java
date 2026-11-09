package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "resume", description = "Start running a source on schedule.", mixinStandardHelpOptions = true)
public final class SourceResumeCommand extends AbstractSourceCommand {

  @Parameters(index = "0", paramLabel = "<id>", description = "Source id.")
  String id;

  @Override
  protected int run(HarvestClient client) {
    client.resume(profile(), id);
    log.info("Resumed source {}.", id);
    return 0;
  }
}
