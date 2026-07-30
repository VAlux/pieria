package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.PieriaCli;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class HookVerbCommandTests {

  private int run(String... args) {
    return new CommandLine(new PieriaCli()).execute(args);
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
}
