package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.RecallResponse;
import dev.alvo.pieria.cli.modules.daemon.ProfileApiClient;
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
  protected int run(ProfileApiClient client) {
    RecallResponse response = client.recall(name, new RecallRequest(query, limit, false, false));

    log.info(response.answer() == null ? "(no answer)" : response.answer());

    List<MemoryResponse> memories = response.memories();
    if (memories != null && !memories.isEmpty()) {
      log.info("");
      log.info("Evidence:");
      for (MemoryResponse m : memories) {
        log.info("  - [{}] {}", m.type(), m.content());
      }
    }

    List<RecallResponse.CodeEvidence> codeEvidence = response.codeEvidence();
    if (codeEvidence != null && !codeEvidence.isEmpty()) {
      log.info("");
      log.info("Code graph evidence:");
      for (RecallResponse.CodeEvidence e : codeEvidence) {
        String target = e.dstPath() == null ? e.dst() : e.dst() + " (" + e.dstPath() + ")";
        log.info("  - {} ({}) {} {} [{}]",
          e.src(), e.srcPath(), e.relation().replace('-', ' '), target, e.confidence());
      }
    }
    return 0;
  }
}
