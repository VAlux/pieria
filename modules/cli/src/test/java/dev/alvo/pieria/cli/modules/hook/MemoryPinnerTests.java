package dev.alvo.pieria.cli.modules.hook;

import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryPinnerTests {

  private HookContext context(String daemonUrl, Path dir) {
    return new HookContext(
      Map.of("PIERIA_DAEMON_URL", daemonUrl, "PIERIA_PROFILE", "proj")::get, dir, "claude-code");
  }

  @ParameterizedTest
  @CsvSource({
    "'plain statement',                       fact,        'plain statement'",
    "'fact: a thing',                         fact,        'a thing'",
    "'instruction: run tests',                instruction, 'run tests'",
    "'event: shipped v2',                     event,       'shipped v2'",
    "'task: write the docs',                  task,        'write the docs'",
    "'fact:no space after colon',             fact,        'no space after colon'",
    "'a sentence: with a colon inside',       fact,        'a sentence: with a colon inside'"
  })
  void parsesLeadingTypePrefix(String raw, String expectedType, String expectedContent) {
    MemoryPinner.Parsed parsed = MemoryPinner.parse(raw);
    assertThat(parsed.type()).isEqualTo(expectedType);
    assertThat(parsed.content()).isEqualTo(expectedContent);
  }

  @Test
  void postsParsedMemoryAndConfirmsOnStdout(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/memories", 200, "{\"id\":\"abc\",\"type\":\"instruction\",\"content\":\"run tests\"}");

      HookOutcome outcome = MemoryPinner.pin(context(daemon.baseUrl(), tmp), "instruction: run tests");

      assertThat(outcome).isInstanceOf(HookOutcome.Ok.class);
      assertThat(((HookOutcome.Ok) outcome).stdout()).contains("instruction").contains("run tests");
      assertThat(daemon.lastRequestTo("/memories").body())
        .contains("\"type\":\"instruction\"").contains("\"content\":\"run tests\"");
    }
  }

  @Test
  void skipsBlankInputWithUsageGuidance(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      HookOutcome outcome = MemoryPinner.pin(context(daemon.baseUrl(), tmp), "   ");

      assertThat(outcome).isInstanceOf(HookOutcome.Skipped.class);
      assertThat(((HookOutcome.Skipped) outcome).reason()).contains("usage:");
      assertThat(daemon.lastRequestTo("/memories")).isNull();
    }
  }

  @Test
  void failsLoudlyWhenDaemonIsDownSoTheUserKnowsNothingPersisted(@TempDir Path tmp) {
    HookOutcome outcome = MemoryPinner.pin(context(StubDaemon.unreachableUrl(), tmp), "a fact");

    assertThat(outcome).isInstanceOf(HookOutcome.Failed.class);
    assertThat(((HookOutcome.Failed) outcome).reason()).contains("NOT stored");
  }
}
