package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.PieriaCli;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HookVerbCommandTests {

  private final PrintStream originalOut = System.out;

  @AfterEach
  void restoreStreams() {
    System.setOut(originalOut);
  }

  private int run(String... args) {
    return new CommandLine(new PieriaCli()).execute(args);
  }

  private String runCapturingStdout(String... args) {
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
  void recallAndRememberAreRegisteredOnTheHookGroup() {
    CommandLine hook = new CommandLine(new PieriaCli()).getSubcommands().get("hook");
    assertThat(hook.getSubcommands().keySet()).contains("recall", "remember");
  }

  @Test
  void recallExitsZeroWithNoDaemon() {
    assertThat(run("hook", "recall", "what changed")).isZero();
  }

  @Test
  void recallExitsZeroWithAnExplicitLimitAndHarness() {
    assertThat(run("hook", "recall", "what changed", "--limit", "3", "--harness", "codex")).isZero();
  }

  @Test
  void rememberExitsZeroWithNoDaemon() {
    assertThat(run("hook", "remember", "instruction: run the tests")).isZero();
  }

  @Test
  void rememberExitsZeroOnEmptyInput() {
    assertThat(run("hook", "remember", "")).isZero();
  }

  @Test
  void rememberReportsFailureOnStdoutSoTheUserKnowsNothingPersisted() {
    String out = runCapturingStdout("hook", "remember", "a fact");

    assertThat(out).contains("NOT stored");
  }

  @Test
  void recallKeepsFailureOffStdoutSoNothingPollutesTheInjectedContext() {
    String out = runCapturingStdout("hook", "recall", "why");

    assertThat(out).isEmpty();
  }
}
