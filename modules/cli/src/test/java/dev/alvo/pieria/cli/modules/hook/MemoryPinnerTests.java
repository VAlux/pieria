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
    assertThat(parsed.topicKey()).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    // The two markers are independent and order-insensitive.
    "'key:embed-dim the dimension is 768',            fact,        embed-dim, 'the dimension is 768'",
    "'fact: key:embed-dim the dimension is 768',      fact,        embed-dim, 'the dimension is 768'",
    "'key:embed-dim fact: the dimension is 768',      fact,        embed-dim, 'the dimension is 768'",
    "'instruction: key:test-cmd run ./gradlew test',  instruction, test-cmd,  'run ./gradlew test'",
    "'key:embed-dim:v2 dotted keys survive',          fact,        embed-dim:v2, 'dotted keys survive'"
  })
  void parsesTopicKeyMarkerInEitherOrder(
    String raw, String expectedType, String expectedKey, String expectedContent) {
    MemoryPinner.Parsed parsed = MemoryPinner.parse(raw);
    assertThat(parsed.type()).isEqualTo(expectedType);
    assertThat(parsed.topicKey()).isEqualTo(expectedKey);
    assertThat(parsed.content()).isEqualTo(expectedContent);
  }

  @ParameterizedTest
  @CsvSource({
    // A bare 'key:' with nothing attached is prose, not a marker.
    "'key: value pairs are cheap to store'",
    "'the key: whichever one we picked'"
  })
  void leavesKeyLikeProseAlone(String raw) {
    MemoryPinner.Parsed parsed = MemoryPinner.parse(raw);
    assertThat(parsed.topicKey()).isNull();
    assertThat(parsed.type()).isEqualTo("fact");
    assertThat(parsed.content()).isEqualTo(raw);
  }

  @Test
  void treatsAKeyWithNoContentAsUsage(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      HookOutcome outcome = MemoryPinner.pin(context(daemon.baseUrl(), tmp), "key:embed-dim");

      assertThat(outcome).isInstanceOf(HookOutcome.Skipped.class);
      assertThat(((HookOutcome.Skipped) outcome).reason()).contains("usage:");
      assertThat(daemon.lastRequestTo("/memories")).isNull();
    }
  }

  @Test
  void sendsTheTopicKeySoTheDeterministicPathCanSupersede(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/memories", 200,
        "{\"id\":\"abc\",\"type\":\"fact\",\"content\":\"768\",\"topicKey\":\"embed-dim\"}");

      HookOutcome outcome =
        MemoryPinner.pin(context(daemon.baseUrl(), tmp), "fact: key:embed-dim the dimension is 768");

      assertThat(outcome).isInstanceOf(HookOutcome.Ok.class);
      assertThat(((HookOutcome.Ok) outcome).stdout()).contains("embed-dim");
      assertThat(daemon.lastRequestTo("/memories").body())
        .contains("\"topicKey\":\"embed-dim\"")
        .contains("\"content\":\"the dimension is 768\"");
    }
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
