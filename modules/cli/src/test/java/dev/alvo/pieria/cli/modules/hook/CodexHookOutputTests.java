package dev.alvo.pieria.cli.modules.hook;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CodexHookOutputTests {

  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void sessionStartWrapsContextInCanonicalCodexJson() {
    HookOutcome outcome = CodexHookOutput.sessionStart(
      new HookOutcome.Ok("[pieria] context with \"quotes\"\nand a second line"));

    assertThat(outcome).isInstanceOf(HookOutcome.Ok.class);
    String stdout = ((HookOutcome.Ok) outcome).stdout();
    assertThat(stdout).startsWith("{").doesNotStartWith("[");
    JsonNode root = json.readTree(stdout);
    assertThat(root.path("hookSpecificOutput").path("hookEventName").asString())
      .isEqualTo("SessionStart");
    assertThat(root.path("hookSpecificOutput").path("additionalContext").asString())
      .isEqualTo("[pieria] context with \"quotes\"\nand a second line");
  }

  @Test
  void sessionStartLeavesEmptyAndDiagnosticOutcomesUnchanged() {
    HookOutcome.Ok empty = new HookOutcome.Ok("");
    HookOutcome.Skipped skipped = new HookOutcome.Skipped("daemon unavailable");
    HookOutcome.Failed failed = new HookOutcome.Failed("recall failed");

    assertThat(CodexHookOutput.sessionStart(empty)).isSameAs(empty);
    assertThat(CodexHookOutput.sessionStart(skipped)).isSameAs(skipped);
    assertThat(CodexHookOutput.sessionStart(failed)).isSameAs(failed);
  }
}
