package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRelevanceFilterTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:00Z");

  private final TraceRelevanceFilter filter = new TraceRelevanceFilter(TraceProperties.defaults());

  private static TraceEvent event(String tool, String args, TraceStatus status, String error) {
    return new TraceEvent("id-" + tool + args + status, "s1", tool, args, "", status,
      status == TraceStatus.FAILURE ? 1 : 0, error, AT, false, 0);
  }

  private static TraceRelevanceFilter.Result run(TraceRelevanceFilter filter, TraceEvent... events) {
    return filter.filter(List.of(events), signature -> Optional.empty());
  }

  private static Memory activeOutcome(String status, String digest) {
    return Memory.of(MemoryType.EVENT, "stored outcome", "s0", "trace:outcome:gradlew-test",
      "{\"source\":\"trace\",\"status\":\"" + status + "\",\"error_digest\":\"" + digest + "\"}");
  }

  @Test
  void successfulDenylistedToolsAreDropped() {
    TraceRelevanceFilter.Result result = run(filter,
      event("Read", "src/Foo.java", TraceStatus.SUCCESS, null),
      event("Grep", "pattern", TraceStatus.SUCCESS, null));

    assertThat(result.kept()).isEmpty();
    assertThat(result.droppedByRule()).containsEntry("denylisted-tool", 2);
  }

  // A failing read is signal: the file is missing or unreadable, and that is worth remembering.
  @Test
  void failuresSurviveTheDenylist() {
    TraceRelevanceFilter.Result result = run(filter,
      event("Read", "missing.java", TraceStatus.FAILURE, "ENOENT"));

    assertThat(result.kept()).hasSize(1);
  }

  @Test
  void bashAndEditsAreAlwaysKept() {
    TraceRelevanceFilter.Result result = run(filter,
      event("Bash", "./gradlew test", TraceStatus.SUCCESS, null),
      event("Edit", "src/Foo.java", TraceStatus.SUCCESS, null),
      event("Write", "src/Bar.java", TraceStatus.SUCCESS, null));

    assertThat(result.kept()).hasSize(3);
  }

  // Only the last run of a command in a batch reflects the current state; earlier ones would
  // supersede each other in arrival order for no benefit.
  @Test
  void repeatsOfOneSignatureAndStatusCollapseToTheLast() {
    TraceEvent first = event("Bash", "./gradlew test", TraceStatus.SUCCESS, null);
    TraceEvent second = event("Bash", "./gradlew test --info", TraceStatus.SUCCESS, null);

    TraceRelevanceFilter.Result result = run(filter, first, second);

    assertThat(result.kept()).containsExactly(second);
    assertThat(result.droppedByRule()).containsEntry("in-batch-repeat", 1);
  }

  @Test
  void aFailureAndASuccessOfOneSignatureBothSurvive() {
    TraceEvent failed = event("Bash", "./gradlew test", TraceStatus.FAILURE, "boom");
    TraceEvent passed = event("Bash", "./gradlew test", TraceStatus.SUCCESS, null);

    assertThat(run(filter, failed, passed).kept()).containsExactly(failed, passed);
  }

  // Re-writing "still passing" every turn is churn with no new information.
  @Test
  void unchangedOutcomeIsSkippedAgainstTheActiveMemory() {
    TraceEvent passed = event("Bash", "./gradlew test", TraceStatus.SUCCESS, null);

    TraceRelevanceFilter.Result result =
      filter.filter(List.of(passed), signature -> Optional.of(activeOutcome("success", "none")));

    assertThat(result.kept()).isEmpty();
    assertThat(result.droppedByRule()).containsEntry("unchanged-outcome", 1);
  }

  @Test
  void aStatusChangeIsNeverSkipped() {
    TraceEvent failed = event("Bash", "./gradlew test", TraceStatus.FAILURE, "boom");

    assertThat(filter.filter(List.of(failed), s -> Optional.of(activeOutcome("success", "none")))
      .kept()).hasSize(1);
  }

  @Test
  void aDifferentFailureIsNeverSkipped() {
    TraceEvent failed = event("Bash", "./gradlew test", TraceStatus.FAILURE, "AssertionError");
    Memory active = activeOutcome("failure", CommandSignature.errorDigest("NullPointerException"));

    assertThat(filter.filter(List.of(failed), s -> Optional.of(active)).kept()).hasSize(1);
  }

  // A hand-written memory sharing the key, or one written before the digest existed, must not
  // silently swallow a real result.
  @Test
  void anActiveMemoryWithoutADigestIsTreatedAsChanged() {
    TraceEvent passed = event("Bash", "./gradlew test", TraceStatus.SUCCESS, null);
    Memory legacy = Memory.of(MemoryType.EVENT, "old", "s0", "trace:outcome:gradlew-test", "{}");

    assertThat(filter.filter(List.of(passed), s -> Optional.of(legacy)).kept()).hasSize(1);
  }

  @Test
  void skipUnchangedCanBeDisabled() {
    TraceProperties d = TraceProperties.defaults();
    TraceRelevanceFilter lenient = new TraceRelevanceFilter(new TraceProperties(
      d.enabled(), d.maxOutputChars(), d.spoolMaxBytes(), d.spoolRetentionDays(),
      d.stopDrainThresholdBytes(), d.stopDrainThresholdEvents(), d.toolDenylist(),
      false, d.recipeExtractionEnabled(), d.maxRecipesPerBatch(), d.maxLinkedSymbols(),
      d.recallBoost()));

    TraceEvent passed = event("Bash", "./gradlew test", TraceStatus.SUCCESS, null);

    assertThat(lenient.filter(List.of(passed), s -> Optional.of(activeOutcome("success", "none")))
      .kept()).hasSize(1);
  }

  @Test
  void emptyInputYieldsEmptyResult() {
    assertThat(filter.filter(List.of(), s -> Optional.empty()).kept()).isEmpty();
    assertThat(filter.filter(null, s -> Optional.empty()).kept()).isEmpty();
  }
}
