package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.domain.graph.GraphFragment;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TraceGraphBuilderTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:00Z");

  private final TraceGraphBuilder builder = new TraceGraphBuilder(8, 8);

  private static TraceEvent event(String tool, String args, TraceStatus status, String error) {
    return new TraceEvent("tid", "s1", tool, args, "", status, status == TraceStatus.FAILURE ? 1 : 0,
      error, AT, false, 0);
  }

  @Test
  void everyTraceLinksItsToolToItsCommand() {
    GraphFragment fragment =
      builder.build(event("Bash", "./gradlew test", TraceStatus.SUCCESS, null));

    assertThat(fragment.triples())
      .containsExactly(new GraphFragment.EdgeTriple(
        "bash", "tool", "invoked", "gradlew test", "command"));
  }

  @Test
  void aFailingTestIsLinkedToTheCommandThatRanIt() {
    GraphFragment fragment = builder.build(event("Bash", "./gradlew test", TraceStatus.FAILURE,
      "GroundingFilterTests > grounded FAILED"));

    assertThat(fragment.triples()).contains(new GraphFragment.EdgeTriple(
      "gradlew test", "command", "failed_in", "groundingfiltertests", "test"));
  }

  // A passing run must not claim a failure edge, or "why did X fail" retrieves a green build.
  @Test
  void aPassingRunEmitsNoFailureEdge() {
    GraphFragment fragment = builder.build(event("Bash", "./gradlew test", TraceStatus.SUCCESS,
      "GroundingFilterTests > grounded FAILED"));

    assertThat(fragment.triples())
      .noneMatch(triple -> triple.relation().equals("failed_in"));
  }

  @Test
  void aModuleQualifiedTaskLinksItsModule() {
    GraphFragment fragment =
      builder.build(event("Bash", "./gradlew :daemon:test", TraceStatus.SUCCESS, null));

    assertThat(fragment.triples()).contains(new GraphFragment.EdgeTriple(
      "gradlew :daemon:test", "command", "validates", "daemon", "module"));
  }

  @Test
  void anEditLinksTheFileItTouched() {
    GraphFragment fragment =
      builder.build(event("Edit", "modules/daemon/src/Foo.java", TraceStatus.SUCCESS, null));

    assertThat(fragment.triples()).contains(new GraphFragment.EdgeTriple(
      "edit modules/daemon/src/foo.java", "command", "touched",
      "modules/daemon/src/foo.java", "file"));
  }

  @Test
  void tripleCountIsCapped() {
    TraceGraphBuilder tight = new TraceGraphBuilder(2, 1);

    GraphFragment fragment = tight.build(event("Bash", "./gradlew :daemon:test",
      TraceStatus.FAILURE, "GroundingFilterTests > grounded FAILED"));

    assertThat(fragment.triples()).hasSize(1);
  }

  @Test
  void aTraceWithNoArgsYieldsAnEmptyFragment() {
    assertThat(builder.build(event("Bash", "  ", TraceStatus.SUCCESS, null)).isEmpty()).isTrue();
  }
}
