package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.api.response.ProfileListResponse;
import dev.alvo.pieria.api.response.ProfileSummary;
import dev.alvo.pieria.cli.modules.daemon.ProfileApiClient;
import picocli.CommandLine.Command;

import java.util.List;

/**
 * {@code pieria profile list} — list all profiles the daemon knows, with their active memory counts.
 */
@Command(
  name = "list",
  description = "List all memory profiles and their active memory counts.",
  mixinStandardHelpOptions = true
)
public final class ProfileListCommand extends AbstractProfileCommand {

  @Override
  protected int run(ProfileApiClient client) {
    ProfileListResponse response = client.listProfiles();
    List<ProfileSummary> profiles = response.profiles();

    if (profiles == null || profiles.isEmpty()) {
      System.out.println("No profiles yet.");
      return 0;
    }

    System.out.printf("%-32s %10s  %s%n", "PROFILE", "MEMORIES", "CREATED");
    for (ProfileSummary p : profiles) {
      System.out.printf("%-32s %10d  %s%n",
        p.name(), p.memoryCount(), p.createdAt() == null ? "—" : p.createdAt());
    }
    return 0;
  }
}
