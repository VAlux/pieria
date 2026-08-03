package dev.alvo.pieria.cli.modules.hook;

import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/** Encodes Codex-specific command-hook output without changing other harness contracts. */
public final class CodexHookOutput {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private CodexHookOutput() {
  }

  /**
   * Wrap recalled SessionStart context in Codex's canonical JSON envelope. Codex treats any stdout
   * beginning with {@code [} as JSON-looking, so forwarding Pieria's plain {@code [pieria]} prefix
   * would otherwise be rejected as malformed hook JSON. Non-output outcomes pass through intact.
   */
  public static HookOutcome sessionStart(HookOutcome outcome) {
    if (!(outcome instanceof HookOutcome.Ok ok) || ok.stdout().isEmpty()) {
      return outcome;
    }

    ObjectNode root = JSON.createObjectNode();
    ObjectNode hookOutput = root.putObject("hookSpecificOutput");
    hookOutput.put("hookEventName", "SessionStart");
    hookOutput.put("additionalContext", ok.stdout());
    return new HookOutcome.Ok(JSON.writeValueAsString(root));
  }
}
