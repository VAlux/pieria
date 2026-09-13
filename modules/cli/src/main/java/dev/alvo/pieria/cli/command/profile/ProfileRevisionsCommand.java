package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.client.ProfileClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "revisions", description = "Show complete assertion revision history.", mixinStandardHelpOptions = true)
public final class ProfileRevisionsCommand extends AbstractProfileCommand {
  @Parameters(index = "0") String name;
  @Parameters(index = "1") String id;
  @Override protected int run(ProfileClient client) {
    log.info(client.toJson(client.revisions(name, id)));
    return 0;
  }
}
