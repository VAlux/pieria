package dev.alvo.pieria.retrieval.rerank;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The re-scorer is the half that runs in every recall tier, so it must be pure arithmetic: no
 * store, no model, no clock. These tests pin the blend, the missing-vector exemption, and the
 * determinism the pipeline downstream assumes.
 */
class SemanticRescorerTests {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private final SemanticRescorer rescorer = new SemanticRescorer();

  private static Memory mem(String id) {
    return new Memory(id, "s1", MemoryType.FACT, "content of " + id, "topic." + id,
      null, false, "{}", null, T0);
  }

  private static RecallCandidate candidate(String id, double score) {
    return new RecallCandidate(mem(id), score, "fts");
  }

  /** A unit vector pointing along axis {@code axis} of a 4-dimensional space. */
  private static float[] axis(int axis) {
    float[] vector = new float[4];
    vector[axis] = 1.0f;
    return vector;
  }

  private static RerankSettings settings(double semanticWeight) {
    return new RerankSettings(true, semanticWeight, true, 30, 400, 4000L);
  }

  private RerankOutcome rescore(List<RecallCandidate> candidates, Map<String, float[]> vectors,
                                float[] query, double weight) {
    return rescorer.rerank(new RerankInput("q", query, candidates, vectors, settings(weight)));
  }

  @Test
  void semanticSignalPromotesALowerRrfCandidate() {
    // "b" is ranked below "a" by RRF but points exactly at the query; "a" is orthogonal to it.
    List<RecallCandidate> input = List.of(candidate("a", 0.9), candidate("b", 0.1));
    Map<String, float[]> vectors = Map.of("a", axis(1), "b", axis(0));

    RerankOutcome outcome = rescore(input, vectors, axis(0), 0.6);

    assertThat(outcome.candidates()).extracting(c -> c.memory().id()).containsExactly("b", "a");
  }

  @Test
  void rrfStillWinsWhenTheSemanticWeightIsSmall() {
    List<RecallCandidate> input = List.of(candidate("a", 0.9), candidate("b", 0.1));
    Map<String, float[]> vectors = Map.of("a", axis(1), "b", axis(0));

    RerankOutcome outcome = rescore(input, vectors, axis(0), 0.2);

    assertThat(outcome.candidates()).extracting(c -> c.memory().id()).containsExactly("a", "b");
  }

  @Test
  void candidatesWithoutAVectorKeepTheirRrfPositionRatherThanScoringZero() {
    // "t" models a task memory: deliberately never embedded. It must not be pushed below a
    // candidate that merely happens to have a vector, or recall quality tracks indexer backlog.
    List<RecallCandidate> input = List.of(candidate("t", 0.9), candidate("b", 0.5));
    Map<String, float[]> vectors = Map.of("b", axis(1));

    RerankOutcome outcome = rescore(input, vectors, axis(0), 0.6);

    assertThat(outcome.candidates()).extracting(c -> c.memory().id()).containsExactly("t", "b");
  }

  @Test
  void scoresAreLeftCarryingTheRrfValue() {
    List<RecallCandidate> input = List.of(candidate("a", 0.9), candidate("b", 0.1));
    Map<String, float[]> vectors = Map.of("a", axis(1), "b", axis(0));

    RerankOutcome outcome = rescore(input, vectors, axis(0), 0.6);

    assertThat(outcome.candidates()).extracting(RecallCandidate::score).containsExactly(0.1, 0.9);
    assertThat(outcome.candidates()).extracting(RecallCandidate::source).containsOnly("fts");
  }

  @Test
  void zeroWeightIsIdentity() {
    List<RecallCandidate> input = List.of(candidate("a", 0.9), candidate("b", 0.1));
    Map<String, float[]> vectors = Map.of("a", axis(1), "b", axis(0));

    RerankOutcome outcome = rescore(input, vectors, axis(0), 0.0);

    assertThat(outcome.candidates()).isEqualTo(input);
    assertThat(outcome.diagnostics().fellBack()).isTrue();
  }

  @Test
  void noQueryEmbeddingIsIdentity() {
    List<RecallCandidate> input = List.of(candidate("a", 0.9), candidate("b", 0.1));

    RerankOutcome outcome = rescore(input, Map.of("a", axis(0)), null, 0.6);

    assertThat(outcome.candidates()).isEqualTo(input);
    assertThat(outcome.diagnostics().fellBack()).isTrue();
  }

  @Test
  void emptyAndSingletonInputsPassThroughUnchanged() {
    assertThat(rescore(List.of(), Map.of(), axis(0), 0.6).candidates()).isEmpty();

    List<RecallCandidate> one = List.of(candidate("a", 0.9));
    assertThat(rescore(one, Map.of("a", axis(0)), axis(0), 0.6).candidates()).isEqualTo(one);
  }

  @Test
  void aWrongWidthVectorKeepsItsRrfPositionRatherThanScoringZero() {
    // Mirrors semanticSignalPromotesALowerRrfCandidate exactly, changing only "a"'s vector from a
    // right-width vector orthogonal to the query to a wrong-width one. Vectors.cosine treats a
    // width mismatch as "no similarity" (0.0) — if blend() used that, "a" would score identically
    // to the sibling test and "b" would be promoted past it there too. Instead blend() diverges
    // deliberately and returns "a"'s normalizedRrf untouched, so it keeps its RRF-top position
    // here while the sibling's real orthogonal vector still lets "b" win. That flip, from the same
    // RRF scores and the same weight, is what tells the test the guard is firing on width and not
    // merely recomputing "no similarity". The window this protects is real: changing
    // pieria.model.embedding-dimension forces a re-embed, and during the drain the store holds
    // old-width and new-width vectors together.
    float[] wrongWidth = new float[] {1.0f, 0.0f, 0.0f};   // 3-wide against a 4-wide query
    List<RecallCandidate> input = List.of(candidate("a", 0.9), candidate("b", 0.1));
    Map<String, float[]> vectors = Map.of("a", wrongWidth, "b", axis(0));

    RerankOutcome outcome = rescore(input, vectors, axis(0), 0.6);

    assertThat(outcome.candidates()).extracting(c -> c.memory().id()).containsExactly("a", "b");
  }

  @Test
  void negativeCosineIsClampedRatherThanSubtracting() {
    // An opposed vector must score the same as no similarity at all, never worse than a candidate
    // with no vector — clamping is what keeps the two comparable.
    float[] opposed = new float[] {-1.0f, 0.0f, 0.0f, 0.0f};
    List<RecallCandidate> input = List.of(candidate("a", 0.5), candidate("b", 0.4));
    Map<String, float[]> vectors = Map.of("a", opposed);

    RerankOutcome outcome = rescore(input, vectors, axis(0), 0.6);

    assertThat(outcome.candidates()).extracting(c -> c.memory().id()).containsExactly("a", "b");
  }

  @Test
  void isDeterministicForAFixedInput() {
    List<RecallCandidate> input = List.of(candidate("a", 0.5), candidate("b", 0.5), candidate("c", 0.5));
    Map<String, float[]> vectors = Map.of("a", axis(0), "b", axis(0), "c", axis(0));

    List<String> first = rescore(input, vectors, axis(0), 0.6).candidates()
      .stream().map(c -> c.memory().id()).toList();
    List<String> second = rescore(input, vectors, axis(0), 0.6).candidates()
      .stream().map(c -> c.memory().id()).toList();

    assertThat(first).isEqualTo(second).containsExactly("a", "b", "c");
  }

  @Test
  void reportsItsOwnDiagnostics() {
    List<RecallCandidate> input = List.of(candidate("a", 0.9), candidate("b", 0.1));

    RerankOutcome outcome = rescore(input, Map.of("a", axis(0), "b", axis(0)), axis(0), 0.6);

    assertThat(outcome.diagnostics().stage()).isEqualTo("semantic");
    assertThat(outcome.diagnostics().input()).isEqualTo(2);
    assertThat(outcome.diagnostics().output()).isEqualTo(2);
    assertThat(outcome.diagnostics().dropped()).isZero();
    assertThat(outcome.diagnostics().fellBack()).isFalse();
  }
}
