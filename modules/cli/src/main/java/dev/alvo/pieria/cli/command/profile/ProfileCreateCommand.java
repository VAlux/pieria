package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.api.response.ProfileSummary;
import dev.alvo.pieria.cli.modules.daemon.ProfileApiClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code pieria profile create <name>} — create a new, empty profile. Fails (exit 1) if a profile
 * with that name already exists.
 */
@Command(
  name = "create",
  description = "Create a new, empty profile.",
  mixinStandardHelpOptions = true
)
public final class ProfileCreateCommand extends AbstractProfileCommand {

  @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
  String name;

  @Override
  protected int run(ProfileApiClient client) {
    ProfileSummary created = client.createProfile(name);
    log.info("Created profile {}", created.name());
    return 0;
  }
}
