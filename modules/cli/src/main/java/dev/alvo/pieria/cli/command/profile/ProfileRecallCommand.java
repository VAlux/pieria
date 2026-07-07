package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.RecallResponse;
import dev.alvo.pieria.api.response.RecallResponse.CodeEvidence;
import dev.alvo.pieria.client.ProfileClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;

/**
 * {@code pieria profile recall <name> <query>} — run retrieval against a profile and print the
 * synthesized answer plus the evidence memories.
 */
@Command(
  name = "recall",
  description = "Recall relevant memories for a query and print the synthesized answer.",
  mixinStandardHelpOptions = true
)
public final class ProfileRecallCommand extends AbstractProfileCommand {

  @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
  String name;

  @Parameters(index = "1", paramLabel = "<query>", description = "Natural-language query.")
  String query;

  @Option(names = "--limit", description = "Maximum number of evidence memories (default: daemon default).")
  Integer limit;

  @Override
  protected int run(ProfileClient client) {
    RecallResponse response = client.recall(name, new RecallRequest(query, limit, false, null));

    log.info(response.answer() == null ? "(no answer)" : response.answer());

    List<MemoryResponse> memories = response.memories();
    if (memories != null && !memories.isEmpty()) {
      log.info("");
      log.info("Evidence:");
      for (MemoryResponse memory : memories) {
        log.info("  - [{}] {}", memory.type(), memory.content());
      }
    }

    List<CodeEvidence> codeEvidence = response.codeEvidence();
    if (codeEvidence != null && !codeEvidence.isEmpty()) {
      log.info("");
      log.info("Code graph evidence:");
      for (CodeEvidence evidence : codeEvidence) {
        String target = evidence.dstPath() == null ? evidence.dst() : "%s (%s)".formatted(evidence.dst(), evidence.dstPath());
        log.info("  - {} ({}) {} {} [{}]",
          evidence.src(), evidence.srcPath(), evidence.relation().replace('-', ' '), target, evidence.confidence());
      }
    }
    return 0;
  }
}
