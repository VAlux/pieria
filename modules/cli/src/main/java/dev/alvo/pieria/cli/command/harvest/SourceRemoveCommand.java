package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "remove", description = "Remove a source and its credentials.", mixinStandardHelpOptions = true)
public final class SourceRemoveCommand extends AbstractSourceCommand {

  @Parameters(index = "0", paramLabel = "<id>", description = "Source id.")
  String id;

  @Option(names = "--forget-memories", description = "Also forget every memory that came from this source.")
  boolean forgetMemories;

  @Option(names = "--yes", description = "Remove without asking for confirmation.")
  boolean yes;

  @Override
  protected int run(HarvestClient client) {
    String question = forgetMemories ? "Remove source " + id + " and forget its memories?" : "Remove source " + id + "? Its memories stay.";
    if (!yes && !confirm(question)) {
      log.error("Not removed. Run again at a terminal to confirm, or pass --yes.");
      return 1;
    }
    client.remove(profile(), id, forgetMemories);
    log.info("Removed source {}.", id);
    return 0;
  }
}
