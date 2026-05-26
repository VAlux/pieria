package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.api.response.ProfileStatsResponse;
import dev.alvo.pieria.cli.modules.daemon.ProfileApiClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Map;

/**
 * {@code pieria profile stats <name>} — print a per-profile snapshot: active memory totals, a
 * by-type breakdown, superseded count, distinct sessions, the createdAt range, and the
 * vectorization backlog.
 */
@Command(
  name = "stats",
  description = "Show statistics for a profile.",
  mixinStandardHelpOptions = true
)
public final class ProfileStatsCommand extends AbstractProfileCommand {

  @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
  String name;

  private static void line(String label, String value) {
    System.out.printf("  %-22s %s%n", label + ":", value);
  }

  @Override
  protected int run(ProfileApiClient client) {
    ProfileStatsResponse s = client.stats(name);

    System.out.printf("Profile: %s%n", s.name());
    line("Created", s.createdAt() == null ? "—" : s.createdAt().toString());
    line("Active memories", Long.toString(s.totalActive()));
    Map<String, Long> byType = s.byType();
    if (byType != null) {
      byType.forEach((type, count) -> line("  " + type, Long.toString(count)));
    }
    line("Superseded", Long.toString(s.superseded()));
    line("Sessions", Long.toString(s.sessions()));
    line("First memory", s.firstMemoryAt() == null ? "—" : s.firstMemoryAt().toString());
    line("Last memory", s.lastMemoryAt() == null ? "—" : s.lastMemoryAt().toString());
    line("Vectorization backlog",
      s.vectorizationBacklog() == null ? "unknown" : s.vectorizationBacklog().toString());
    return 0;
  }
}
