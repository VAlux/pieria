package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.cli.PieriaCli;
import dev.alvo.pieria.cli.modules.hook.TraceSpool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodexPostToolUseCommandTests {

  @Test
  void postToolUseIsRegistered() {
    CommandLine codex = new CommandLine(new PieriaCli())
      .getSubcommands().get("hook").getSubcommands().get("codex");

    assertThat(codex.getSubcommands().keySet()).contains("post-tool-use", "session-end");
  }

  @Test
  void unusableStdinStillExitsZero() {
    for (String stdin : new String[] {"", "not-json", "[]", "{}"}) {
      assertThat(HookTestSupport.runWithStdin(stdin, "hook", "codex", "post-tool-use")
        .exitCode()).isZero();
    }
  }

  @Test
  void documentedBashPayloadSpoolsTraceWithoutInventingSuccess(@TempDir Path home) {
    TraceEventDto event = runAndDrain(home, """
      {"session_id":"s1","hook_event_name":"PostToolUse","tool_name":"Bash",
       "tool_input":{"command":"./gradlew test"},
       "tool_response":"BUILD SUCCESSFUL\\n"}
      """);

    assertThat(event.tool()).isEqualTo("Bash");
    assertThat(event.args()).contains("./gradlew test");
    assertThat(event.output()).contains("BUILD SUCCESSFUL");
    assertThat(event.status()).isEqualTo(TraceStatus.UNKNOWN);
    assertThat(event.exitCode()).isNull();
    assertThat(event.endedAt()).isNotNull();
  }

  @Test
  void postToolUseMarksNonShellResultSuccessful(@TempDir Path home) {
    TraceEventDto event = runAndDrain(home, """
      {"session_id":"s1","hook_event_name":"PostToolUse","tool_name":"apply_patch",
       "tool_input":{"command":"*** Begin Patch"},
       "tool_response":"Done!"}
      """);

    assertThat(event.status()).isEqualTo(TraceStatus.SUCCESS);
  }

  @Test
  void futureStructuredExitCodeTakesPrecedence(@TempDir Path home) {
    TraceEventDto event = runAndDrain(home, """
      {"session_id":"s1","hook_event_name":"PostToolUse","tool_name":"Bash",
       "tool_input":{"command":"false"},
       "tool_response":{"output":"","exit_code":1}}
      """);

    assertThat(event.status()).isEqualTo(TraceStatus.FAILURE);
    assertThat(event.exitCode()).isEqualTo(1);
  }

  private TraceEventDto runAndDrain(Path home, String stdin) {
    String originalHome = System.getProperty("user.home");
    try {
      System.setProperty("user.home", home.toString());
      assertThat(HookTestSupport.runWithStdin(
        stdin, "hook", "codex", "post-tool-use").exitCode()).isZero();
      return new TraceSpool(TraceSpool.defaultRoot()).drain("s1").getFirst();
    } finally {
      if (originalHome == null) {
        System.clearProperty("user.home");
      } else {
        System.setProperty("user.home", originalHome);
      }
    }
  }
}
