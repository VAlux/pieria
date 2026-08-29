package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.api.request.TraceStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TraceEventTests {

  private static final Instant STARTED = Instant.parse("2026-08-29T10:00:00Z");
  private static final Instant ENDED = Instant.parse("2026-08-29T10:00:07Z");
  private static final Instant RECEIPT = Instant.parse("2026-08-29T12:00:00Z");
  private static final Path REPO = Path.of("/repo");
  private static final Path HOME = Path.of("/home/dev");

  private static TraceEvent from(TraceEventDto dto) {
    return TraceEvent.from("p1", "s1", dto, 4000, REPO, HOME, RECEIPT);
  }

  @Test
  void endedAtWinsAsTheEventTime() {
    TraceEvent event = from(new TraceEventDto(
      "Bash", "./gradlew test", "ok", TraceStatus.SUCCESS, 0, null, STARTED, ENDED));

    assertThat(event.occurredAt()).isEqualTo(ENDED);
    assertThat(event.occurredAtFromReceipt()).isFalse();
  }

  @Test
  void startedAtIsTheFirstFallback() {
    TraceEvent event = from(new TraceEventDto(
      "Bash", "./gradlew test", "ok", TraceStatus.SUCCESS, 0, null, STARTED, null));

    assertThat(event.occurredAt()).isEqualTo(STARTED);
    assertThat(event.occurredAtFromReceipt()).isFalse();
  }

  // A trace with no timestamps must be distinguishable from one that genuinely ran at ingest time,
  // or a late-drained spool misorders against supersession.
  @Test
  void receiptTimeIsTheLastResortAndIsFlagged() {
    TraceEvent event = from(new TraceEventDto(
      "Bash", "./gradlew test", "ok", TraceStatus.SUCCESS, 0, null, null, null));

    assertThat(event.occurredAt()).isEqualTo(RECEIPT);
    assertThat(event.occurredAtFromReceipt()).isTrue();
  }

  @Test
  void argsAndOutputAreRedactedAndPathsNormalized() {
    TraceEvent event = from(new TraceEventDto(
      "Bash", "cd /repo/src && TOKEN=abcdef123456 ./run.sh",
      "wrote /home/dev/.cache/x", TraceStatus.SUCCESS, 0, null, STARTED, ENDED));

    assertThat(event.args()).doesNotContain("abcdef123456");
    assertThat(event.args()).contains("./src");
    assertThat(event.output()).contains("~/.cache/x");
    assertThat(event.redactionHits()).isEqualTo(1);
  }

  @Test
  void outputIsCappedAtTheBudget() {
    TraceEvent event = TraceEvent.from("p1", "s1", new TraceEventDto(
      "Bash", "run", "z".repeat(10_000), TraceStatus.SUCCESS, 0, null, STARTED, ENDED),
      200, REPO, HOME, RECEIPT);

    assertThat(event.output().length()).isLessThan(400);
  }

  @Test
  void bashInvocationIsTheCommandAlone() {
    TraceEvent event = from(new TraceEventDto(
      "Bash", "./gradlew test", "", TraceStatus.SUCCESS, 0, null, STARTED, ENDED));

    assertThat(event.invocation()).isEqualTo("./gradlew test");
    assertThat(event.signature()).isEqualTo("gradlew-test");
  }

  @Test
  void nonBashInvocationNamesTheTool() {
    TraceEvent event = from(new TraceEventDto(
      "Edit", "src/Foo.java", "", TraceStatus.SUCCESS, null, null, STARTED, ENDED));

    assertThat(event.invocation()).isEqualTo("Edit src/Foo.java");
  }

  // The signal line is what a failure memory quotes, so error beats output and the first
  // non-blank line beats the rest.
  @Test
  void signalLinePrefersErrorOverOutput() {
    TraceEvent event = from(new TraceEventDto(
      "Bash", "./gradlew test", "compiling\nBUILD FAILED", TraceStatus.FAILURE, 1,
      "\n\nGroundingFilterTests > grounded FAILED\n  at Foo.java:1", STARTED, ENDED));

    assertThat(event.signalLine()).isEqualTo("GroundingFilterTests > grounded FAILED");
  }

  @Test
  void signalLineFallsBackToTheLastOutputLineThenToAPlaceholder() {
    TraceEvent withOutput = from(new TraceEventDto(
      "Bash", "x", "compiling\nBUILD FAILED\n", TraceStatus.FAILURE, 1, null, STARTED, ENDED));
    TraceEvent withNothing = from(new TraceEventDto(
      "Bash", "x", "  ", TraceStatus.FAILURE, 1, null, STARTED, ENDED));

    assertThat(withOutput.signalLine()).isEqualTo("BUILD FAILED");
    assertThat(withNothing.signalLine()).isEqualTo("no output captured");
  }

  @Test
  void idIsContentAddressedOverTheRedactedArgs() {
    TraceEventDto dto = new TraceEventDto(
      "Bash", "./gradlew test", "ok", TraceStatus.SUCCESS, 0, null, STARTED, ENDED);

    assertThat(from(dto).id()).isEqualTo(from(dto).id()).hasSize(32);
  }
}
