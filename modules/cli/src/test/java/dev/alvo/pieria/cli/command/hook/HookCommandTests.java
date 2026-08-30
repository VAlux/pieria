package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.PieriaCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

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

  // These pin the fail-closed exit-0 contract on the paths that stop before any HTTP call, and
  // assert on the skip diagnostic so the two branches stay distinguishable — the whole bug was an
  // ingest that resolved no transcript and said so only on stderr.
  // Command-level tests cannot reach a stub daemon here because HookContext.create() reads the
  // real process environment; daemon-failure behavior (unreachable / non-2xx) is covered one
  // layer down in TranscriptIngestorTests. Every case below therefore resolves to a missing
  // transcript, which is skipped without contacting a daemon.
  @Test
  void ingestHookExitsZeroAndReportsNoPayloadWhenStdinIsUnusable() {
    for (String stdin : new String[] {"", "not-json", "[]"}) {
      HookTestSupport.Run run = HookTestSupport.runWithStdin(stdin, "hook", "claude-code", "stop");

      assertThat(run.exitCode()).isZero();
      assertThat(run.stderr()).contains("no transcript_path in the hook stdin payload");
    }
  }

  // The regression guard: a payload-supplied path must be resolved, so the skip has to come from
  // the file being absent rather than from no path having been found at all.
  @Test
  void ingestHookResolvesTheTranscriptPathFromTheStdinPayload(@TempDir Path tmp) {
    String payload = "{\"session_id\":\"s1\",\"transcript_path\":\""
      + tmp.resolve("gone.jsonl").toString().replace("\\", "\\\\") + "\"}";

    for (String harness : new String[] {"claude-code", "codex"}) {
      HookTestSupport.Run run = HookTestSupport.runWithStdin(payload, "hook", harness, "stop");

      assertThat(run.exitCode()).isZero();
      assertThat(run.stderr())
        .contains("transcript not found")
        .contains("gone.jsonl")
        .doesNotContain("no transcript_path in the hook stdin payload");
    }
  }

  @Test
  void sessionStartExitsZeroWhenDaemonIsUnreachable() {
    assertThat(run("hook", "claude-code", "session-start")).isZero();
  }
}
