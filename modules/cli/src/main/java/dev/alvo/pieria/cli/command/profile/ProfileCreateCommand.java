package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.api.response.ProfileSummary;
import dev.alvo.pieria.client.ProfileClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code pieria profile create <name> [--parent <profile>]} — create a new, empty profile, at top
 * level or under an existing profile. Fails (exit 1) if a profile with that name already exists, and
 * (exit 4) if the parent does not.
 */
@Command(
  name = "create",
  description = "Create a new, empty profile.",
  mixinStandardHelpOptions = true
)
public final class ProfileCreateCommand extends AbstractProfileCommand {

  @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
  String name;

  @Option(names = "--parent", paramLabel = "<profile>",
    description = "Create it under this profile; a recall on the parent then includes its memories.")
  String parent;

  @Override
  protected int run(ProfileClient client) {
    ProfileSummary created = client.create(name, parent);
    if (created.parent() == null) {
      log.info("Created profile {}", created.name());
    } else {
      log.info("Created profile {} under {}", created.name(), created.parent());
    }
    return 0;
  }
}
