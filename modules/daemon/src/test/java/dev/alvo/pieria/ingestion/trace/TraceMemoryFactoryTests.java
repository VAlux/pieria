package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryTimes;
import dev.alvo.pieria.domain.memory.MemoryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceMemoryFactoryTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:07Z");

  private static TraceEvent event(TraceStatus status, Integer exitCode, String error, String output) {
    return new TraceEvent("tid", "s1", "Bash", "./gradlew test", output, status, exitCode, error,
      AT, false, 0);
  }

  @Test
  void failureContentQuotesTheCommandExitCodeAndSignalLine() {
    Memory memory = TraceMemoryFactory.outcome(
      event(TraceStatus.FAILURE, 1, "GroundingFilterTests > grounded FAILED", ""), List.of());

    assertThat(memory.type()).isEqualTo(MemoryType.EVENT);
    assertThat(memory.content())
      .isEqualTo("`./gradlew test` failed (exit 1): GroundingFilterTests > grounded FAILED");
  }

  @Test
  void successContentIsTerse() {
    Memory memory = TraceMemoryFactory.outcome(event(TraceStatus.SUCCESS, 0, null, "ok"), List.of());

    assertThat(memory.content()).isEqualTo("`./gradlew test` succeeded (exit 0)");
  }

  @Test
  void unknownStatusSaysSo() {
    Memory memory =
      TraceMemoryFactory.outcome(event(TraceStatus.UNKNOWN, null, null, ""), List.of());

    assertThat(memory.content()).isEqualTo("`./gradlew test` ran; outcome unknown");
  }

  @Test
  void missingExitCodeIsOmittedRatherThanGuessed() {
    Memory memory = TraceMemoryFactory.outcome(event(TraceStatus.FAILURE, null, "boom", ""), List.of());

    assertThat(memory.content()).isEqualTo("`./gradlew test` failed: boom");
  }

  // The key is what makes run n+1 supersede run n through the existing machinery.
  @Test
  void outcomeIsKeyedBySignature() {
    Memory memory = TraceMemoryFactory.outcome(event(TraceStatus.SUCCESS, 0, null, ""), List.of());

    assertThat(memory.topicKey()).isEqualTo("trace:outcome:gradlew-test");
  }

  @Test
  void payloadCarriesTheTraceProvenanceContract() {
    Memory memory = TraceMemoryFactory.outcome(
      event(TraceStatus.FAILURE, 1, "boom", ""), List.of("sym1", "sym2"));

    assertThat(memory.payload())
      .contains("\"source\":\"trace\"")
      .contains("\"tool\":\"Bash\"")
      .contains("\"command\":\"./gradlew test\"")
      .contains("\"status\":\"failure\"")
      .contains("\"exit_code\":1")
      .contains("\"error_digest\":\"" + CommandSignature.errorDigest("boom") + "\"")
      .contains("\"symbolIds\":[\"sym1\",\"sym2\"]");
  }

  // Both times come from the trace, never from the store clock: occurred_at because the command
  // genuinely ran then, stated_at because supersession ordering reads it.
  @Test
  void bothTimesComeFromTheTraceNotTheStoreClock() {
    Memory memory = TraceMemoryFactory.outcome(event(TraceStatus.SUCCESS, 0, null, ""), List.of());

    assertThat(memory.payload()).contains("\"occurred_at\":\"2026-08-29T10:00:07Z\"");
    assertThat(memory.payload()).contains("\"stated_at\":\"2026-08-29T10:00:07Z\"");
    assertThat(MemoryTimes.knowledgeTime(memory)).isEqualTo(AT);
    assertThat(MemoryTimes.anchor(memory)).isEqualTo(AT.atZone(java.time.ZoneOffset.UTC).toLocalDate());
  }

  @Test
  void emptySymbolIdsAreOmittedFromThePayload() {
    Memory memory = TraceMemoryFactory.outcome(event(TraceStatus.SUCCESS, 0, null, ""), List.of());

    assertThat(memory.payload()).doesNotContain("symbolIds");
  }

  // embed_text pairs the declarative statement with the questions an agent actually asks, so a
  // procedural trace surfaces under natural phrasing rather than only under its command string.
  @Test
  void embedTextAddsDeterministicInterrogatives() {
    Memory memory = TraceMemoryFactory.outcome(event(TraceStatus.FAILURE, 1, "boom", ""), List.of());

    assertThat(memory.embedText())
      .contains(memory.content())
      .contains("how do I run `./gradlew test`")
      .contains("does `./gradlew test` pass")
      .contains("why does `./gradlew test` fail");
  }

  @Test
  void successEmbedTextOmitsTheFailureQuestion() {
    Memory memory = TraceMemoryFactory.outcome(event(TraceStatus.SUCCESS, 0, null, ""), List.of());

    assertThat(memory.embedText()).doesNotContain("why does");
  }

  @Test
  void recipeIsAKeyedInstruction() {
    Memory memory = TraceMemoryFactory.recipe(
      "Tests in this repo are run with ./gradlew test.", "gradlew-test", AT, List.of("sym1"));

    assertThat(memory.type()).isEqualTo(MemoryType.INSTRUCTION);
    assertThat(memory.topicKey()).isEqualTo("trace:recipe:gradlew-test");
    assertThat(memory.content()).isEqualTo("Tests in this repo are run with ./gradlew test.");
    assertThat(memory.payload()).contains("\"source\":\"trace\"").contains("\"symbolIds\":[\"sym1\"]");
    assertThat(MemoryTimes.knowledgeTime(memory)).isEqualTo(AT);
  }

  // The raw row is retrieval evidence; MessageFtsChannel searches it, so it must carry the
  // command and the output, not just a summary label.
  @Test
  void rawMessageContentCarriesCommandStatusAndOutput() {
    String raw = TraceMemoryFactory.rawMessageContent(
      event(TraceStatus.FAILURE, 1, "boom", "compiling"));

    assertThat(raw)
      .contains("Bash")
      .contains("./gradlew test")
      .contains("failure")
      .contains("compiling")
      .contains("boom");
  }
}
