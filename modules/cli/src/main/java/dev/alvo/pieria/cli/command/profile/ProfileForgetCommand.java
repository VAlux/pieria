package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.cli.modules.daemon.ProfileApiClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code pieria profile forget <name> <id>} — delete (supersede) a single memory by id.
 */
@Command(
  name = "forget",
  description = "Delete a memory by id.",
  mixinStandardHelpOptions = true
)
public final class ProfileForgetCommand extends AbstractProfileCommand {

  @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
  String name;

  @Parameters(index = "1", paramLabel = "<id>", description = "Memory id to forget.")
  String id;

  @Override
  protected int run(ProfileApiClient client) {
    client.forget(name, id);
    System.out.printf("Forgot %s%n", id);
    return 0;
  }
}
