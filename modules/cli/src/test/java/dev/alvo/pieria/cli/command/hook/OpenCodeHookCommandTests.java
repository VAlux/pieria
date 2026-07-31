package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.PieriaCli;
import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeHookCommandTests {

  private final InputStream originalIn = System.in;
  private final PrintStream originalOut = System.out;

  @AfterEach
  void restoreStreams() {
    System.setIn(originalIn);
    System.setOut(originalOut);
  }

  private String runCapturingStdout(String stdin, String... args) {
    System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      int code = new CommandLine(new PieriaCli()).execute(args);
      assertThat(code).isZero();
    } finally {
      System.setOut(originalOut);
    }
    return captured.toString(StandardCharsets.UTF_8);
  }

  @Test
  void codexAndOpencodeEventsAreRegistered() {
    CommandLine hook = new CommandLine(new PieriaCli()).getSubcommands().get("hook");

    assertThat(hook.getSubcommands().get("codex").getSubcommands().keySet())
      .contains("session-start", "stop");
    assertThat(hook.getSubcommands().get("opencode").getSubcommands().keySet())
      .contains("ingest", "recall-transform");
  }

  @Test
  void recallTransformEchoesSystemPromptEvenWhenDaemonIsDown() {
    String out = runCapturingStdout("ORIGINAL SYSTEM PROMPT", "hook", "opencode", "recall-transform");

    assertThat(out).startsWith("ORIGINAL SYSTEM PROMPT");
    assertThat(out).doesNotContain("Prior project context");
  }

  @Test
  void recallTransformPassesMultilinePromptsThroughByteForByte() {
    String out = runCapturingStdout("PROMPT ONE\nPROMPT TWO", "hook", "opencode", "recall-transform");

    // With no daemon reachable nothing is appended, so the output is exactly the input. The
    // append path needs a stub daemon the command cannot be pointed at (it reads the real
    // PIERIA_DAEMON_URL), and is covered by ContextRecallerTests instead.
    assertThat(out).isEqualTo("PROMPT ONE\nPROMPT TWO");
  }

  @Test
  void ingestReadsStdinAndExitsZeroWithNoDaemon() {
    String out = runCapturingStdout("{\"role\":\"user\"}\n", "hook", "opencode", "ingest");
    assertThat(out).isEmpty();
  }

  @Test
  void codexStopReadsJsonStdinAndExitsZeroWhenTranscriptIsMissing() {
    String out = runCapturingStdout(
      "{\"session_id\":\"thr_123\",\"transcript_path\":null}",
      "hook", "codex", "stop");

    assertThat(out).isEmpty();
  }
}
