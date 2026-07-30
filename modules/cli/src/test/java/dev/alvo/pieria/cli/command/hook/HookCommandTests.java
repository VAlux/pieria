package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.PieriaCli;
import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class HookCommandTests {

  private int run(String... args) {
    return new CommandLine(new PieriaCli()).execute(args);
  }

  @Test
  void hookGroupIsRegisteredButHiddenFromHelp() {
    CommandLine cli = new CommandLine(new PieriaCli());
    CommandLine hook = cli.getSubcommands().get("hook");

    assertThat(hook).isNotNull();
    assertThat(hook.getCommandSpec().usageMessage().hidden()).isTrue();
    assertThat(cli.getUsageMessage()).doesNotContain("hook");
  }

  @Test
  void claudeCodeEventsAreAllRegistered() {
    CommandLine claudeCode = new CommandLine(new PieriaCli())
      .getSubcommands().get("hook")
      .getSubcommands().get("claude-code");

    assertThat(claudeCode.getSubcommands().keySet())
      .contains("session-start", "pre-compact", "stop", "session-end");
  }

  @Test
  void ingestHookExitsZeroWhenDaemonIsUnreachable() {
    assertThat(run("hook", "claude-code", "stop")).isZero();
  }

  @Test
  void sessionStartExitsZeroWhenDaemonIsUnreachable() {
    assertThat(run("hook", "claude-code", "session-start")).isZero();
  }

  @Test
  void ingestHookExitsZeroWhenDaemonRejectsThePayload() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/ingest/transcript", 400, "{\"message\":\"nope\"}");
      assertThat(run("hook", "claude-code", "stop")).isZero();
    }
  }
}
