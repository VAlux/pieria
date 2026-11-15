package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.api.response.ProfileSummary;
import dev.alvo.pieria.client.ProfileClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code pieria profile parent <name> <parent>} — put a profile under another one, so a recall on the
 * parent also reads its memories. {@code --none} moves it back to top level. Memories never move:
 * each agent keeps writing to the profile it always wrote to. A parent that would create a cycle (the
 * profile itself, or one of its own sub-profiles) is refused with exit 1.
 */
@Command(
  name = "parent",
  description = "Set the profile a profile sits under, or move it to top level with --none.",
  mixinStandardHelpOptions = true
)
public final class ProfileParentCommand extends AbstractProfileCommand {

  @Parameters(index = "0", paramLabel = "<name>", description = "Profile to move.")
  String name;

  @Parameters(index = "1", arity = "0..1", paramLabel = "<parent>", description = "Profile to put it under.")
  String parent;

  @Option(names = "--none", description = "Move it to top level.")
  boolean none;

  @Override
  protected int run(ProfileClient client) {
    if (none == (parent != null)) {
      log.error("Give either a parent profile or --none.");
      return 2;
    }
    ProfileSummary moved = client.setParent(name, parent);
    if (moved.parent() == null) {
      log.info("Profile {} is now at top level", moved.name());
    } else {
      log.info("Profile {} is now under {}", moved.name(), moved.parent());
    }
    return 0;
  }
}
