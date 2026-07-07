package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.api.request.RememberRequest;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.client.ProfileClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code pieria profile remember <name> --type --content [...]} — store a single memory explicitly
 * in a profile.
 */
@Command(
  name = "remember",
  description = "Store a single memory explicitly.",
  mixinStandardHelpOptions = true
)
public final class ProfileRememberCommand extends AbstractProfileCommand {

  @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
  String name;

  @Option(names = "--type", required = true, description = "Memory type: fact, event, instruction, task.")
  String type;

  @Option(names = "--content", required = true, description = "The memory's canonical statement.")
  String content;

  @Option(names = "--topic-key", description = "Normalized key for keyed facts/instructions (enables supersession).")
  String topicKey;

  @Option(names = "--session", description = "Session id to associate with the memory.")
  String session;

  @Option(names = "--payload", description = "Opaque JSON payload to store alongside the memory.")
  String payload;

  @Override
  protected int run(ProfileClient client) {
    MemoryResponse stored = client.remember(name,
      new RememberRequest(type, content, session, topicKey, payload));
    log.info("Stored {} [{}]", stored.id(), stored.type());
    return 0;
  }
}
