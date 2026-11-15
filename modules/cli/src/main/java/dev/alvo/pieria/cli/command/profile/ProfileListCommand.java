package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.api.response.ProfileListResponse;
import dev.alvo.pieria.api.response.ProfileSummary;
import dev.alvo.pieria.client.ProfileClient;
import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code pieria profile list} — list all profiles the daemon knows as a tree, with their own active
 * memory counts. A profile sits under its parent; a recall on a parent also reads everything below it.
 */
@Command(
  name = "list",
  description = "List all memory profiles as a tree, with their active memory counts.",
  mixinStandardHelpOptions = true
)
public final class ProfileListCommand extends AbstractProfileCommand {

  @Override
  protected int run(ProfileClient client) {
    ProfileListResponse response = client.list();
    List<ProfileSummary> profiles = response.profiles();

    if (profiles == null || profiles.isEmpty()) {
      log.info("No profiles yet.");
      return 0;
    }

    log.info(String.format("%-32s %10s  %s", "PROFILE", "MEMORIES", "CREATED"));
    for (Row row : rows(profiles)) {
      ProfileSummary p = row.profile();
      log.info(String.format("%-32s %10d  %s",
        row.prefix() + p.name(), p.memoryCount(), p.createdAt() == null ? "—" : p.createdAt()));
    }
    return 0;
  }

  record Row(String prefix, ProfileSummary profile) {
  }

  /**
   * The profiles depth-first, each child under its parent, siblings by name. A profile whose parent
   * is not in the listing is shown at top level rather than dropped.
   */
  static List<Row> rows(List<ProfileSummary> profiles) {
    Map<String, ProfileSummary> byName = new LinkedHashMap<>();
    profiles.forEach(p -> byName.put(p.name(), p));
    Map<String, List<ProfileSummary>> children = new LinkedHashMap<>();
    List<ProfileSummary> roots = new ArrayList<>();
    for (ProfileSummary p : profiles) {
      if (p.parent() != null && byName.containsKey(p.parent())) {
        children.computeIfAbsent(p.parent(), _ -> new ArrayList<>()).add(p);
      } else {
        roots.add(p);
      }
    }
    List<Row> rows = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (ProfileSummary root : sorted(roots)) {
      walk(root, "", "", children, rows, seen);
    }
    return rows;
  }

  private static void walk(ProfileSummary profile, String prefix, String indent,
                           Map<String, List<ProfileSummary>> children, List<Row> rows, Set<String> seen) {
    if (!seen.add(profile.name())) {
      return;
    }
    rows.add(new Row(prefix, profile));
    List<ProfileSummary> kids = sorted(children.getOrDefault(profile.name(), List.of()));
    for (int i = 0; i < kids.size(); i++) {
      boolean last = i == kids.size() - 1;
      walk(kids.get(i), indent + (last ? "└─ " : "├─ "), indent + (last ? "   " : "│  "), children, rows, seen);
    }
  }

  private static List<ProfileSummary> sorted(List<ProfileSummary> profiles) {
    return profiles.stream().sorted(Comparator.comparing(ProfileSummary::name)).toList();
  }
}
