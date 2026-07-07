package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.api.response.MemoryListResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.client.ProfileClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;

/**
 * {@code pieria profile memories <name> [--type] [--session]} — list a profile's active memories,
 * optionally filtered by type and/or session.
 */
@Command(
  name = "memories",
  description = "List a profile's memories, optionally filtered by type and/or session.",
  mixinStandardHelpOptions = true
)
public final class ProfileMemoriesCommand extends AbstractProfileCommand {

  @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
  String name;

  @Option(names = "--type", description = "Filter by memory type: fact, event, instruction, task.")
  String type;

  @Option(names = "--session", description = "Filter by session id.")
  String session;

  @Override
  protected int run(ProfileClient client) {
    MemoryListResponse response = client.memories(name, type, session);
    List<MemoryResponse> memories = response.memories();

    if (memories == null || memories.isEmpty()) {
      log.info("No memories.");
      return 0;
    }

    for (MemoryResponse m : memories) {
      log.info("{}  [{}]{}", m.id(), m.type(),
        m.topicKey() == null || m.topicKey().isBlank() ? "" : " {" + m.topicKey() + "}");
      log.info("    {}", m.content());
    }
    log.info("");
    log.info("{} memor{}.", memories.size(), memories.size() == 1 ? "y" : "ies");
    return 0;
  }
}
