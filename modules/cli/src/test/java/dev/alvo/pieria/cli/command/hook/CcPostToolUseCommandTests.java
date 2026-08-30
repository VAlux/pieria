package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.PieriaCli;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class CcPostToolUseCommandTests {

  @Test
  void postToolUseIsRegistered() {
    CommandLine claudeCode = new CommandLine(new PieriaCli())
      .getSubcommands().get("hook").getSubcommands().get("claude-code");

    assertThat(claudeCode.getSubcommands().keySet()).contains("post-tool-use");
  }

  // The fail-closed contract: whatever stdin carries, the hook exits 0 and never breaks the
  // session it is embedded in.
  @Test
  void unusableStdinStillExitsZero() {
    for (String stdin : new String[] {"", "not-json", "[]", "{}"}) {
      assertThat(HookTestSupport.runWithStdin(stdin, "hook", "claude-code", "post-tool-use")
        .exitCode()).isZero();
    }
  }
}
