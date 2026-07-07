package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.client.ProfileClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code pieria profile delete <name>} — permanently delete a profile and every memory it holds.
 * This is a hard, irreversible delete (not the logical supersession used by {@code forget}).
 */
@Command(
  name = "delete",
  description = "Delete a profile and all of its memories.",
  mixinStandardHelpOptions = true
)
public final class ProfileDeleteCommand extends AbstractProfileCommand {

  @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
  String name;

  @Override
  protected int run(ProfileClient client) {
    client.delete(name);
    log.info("Deleted profile {} and all of its memories", name);
    return 0;
  }
}
