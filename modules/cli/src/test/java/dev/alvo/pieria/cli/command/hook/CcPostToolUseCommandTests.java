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

  @Test
  void documentedPostToolUsePayloadSpoolsSuccessWithoutAnExitCode(@TempDir Path home) {
    TraceEventDto event = runAndDrain(home, """
      {"session_id":"s1","hook_event_name":"PostToolUse","tool_name":"Bash",
       "tool_input":{"command":"./gradlew test"},
       "tool_response":{"stdout":"BUILD SUCCESSFUL","stderr":"","interrupted":false,"isImage":false}}
      """);

    assertThat(event.tool()).isEqualTo("Bash");
    assertThat(event.args()).contains("./gradlew test");
    assertThat(event.output()).contains("BUILD SUCCESSFUL");
    assertThat(event.status()).isEqualTo(TraceStatus.SUCCESS);
    assertThat(event.exitCode()).isNull();
    assertThat(event.error()).isNull();
    assertThat(event.startedAt()).isNull();
    assertThat(event.endedAt()).isNotNull();
  }

  @Test
  void documentedPostToolUseFailurePayloadSpoolsFailureAndError(@TempDir Path home) {
    TraceEventDto event = runAndDrain(home, """
      {"session_id":"s1","hook_event_name":"PostToolUseFailure","tool_name":"Bash",
       "tool_input":{"command":"./gradlew test"},
       "error":"Command exited with non-zero status code 1","is_interrupt":false}
      """);

    assertThat(event.status()).isEqualTo(TraceStatus.FAILURE);
    assertThat(event.output()).isNull();
    assertThat(event.exitCode()).isNull();
    assertThat(event.error()).isEqualTo("Command exited with non-zero status code 1");
  }

  @Test
  void legacyPayloadFallsBackToItsNumericExitCode(@TempDir Path home) {
    TraceEventDto event = runAndDrain(home, """
      {"session_id":"s1","tool_name":"Bash",
       "tool_input":{"command":"./gradlew test"},
       "tool_response":{"stdout":"BUILD FAILED","exitCode":1}}
      """);

    assertThat(event.status()).isEqualTo(TraceStatus.FAILURE);
    assertThat(event.exitCode()).isEqualTo(1);
  }

  private TraceEventDto runAndDrain(Path home, String stdin) {
    String originalHome = System.getProperty("user.home");
    try {
      System.setProperty("user.home", home.toString());
      assertThat(HookTestSupport.runWithStdin(
        stdin, "hook", "claude-code", "post-tool-use").exitCode()).isZero();
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
