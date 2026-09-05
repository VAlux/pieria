package dev.alvo.pieria.retrieval.rerank;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.RerankLabel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model reranker's job is to be useful when the model cooperates and invisible when it does
 * not. Most of these tests are about the second half.
 */
class ModelRerankerTests {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static Memory mem(String id) {
    return new Memory(id, "s1", MemoryType.FACT, "content of " + id, "topic." + id,
      null, false, "{}", null, T0);
  }

  private static RecallCandidate candidate(String id) {
    return new RecallCandidate(mem(id), 1.0, "fts");
  }

  private static RerankSettings settings() {
    return new RerankSettings(true, 0.4, true, 30, 400, 4000L);
  }

  private static RerankInput input(List<RecallCandidate> candidates) {
    return new RerankInput("q", null, candidates, Map.of(), settings());
  }

  private static List<String> ids(RerankOutcome outcome) {
    return outcome.candidates().stream().map(c -> c.memory().id()).toList();
  }

  @Test
  void essentialOutranksRelatedAndIrrelevantIsDropped() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.RELATED, RerankLabel.IRRELEVANT, RerankLabel.ESSENTIAL));
    ModelReranker reranker = new ModelReranker(model);

    RerankOutcome outcome = reranker.rerank(
      input(List.of(candidate("a"), candidate("b"), candidate("c"))));

    assertThat(ids(outcome)).containsExactly("c", "a");
    assertThat(outcome.diagnostics().stage()).isEqualTo("model");
    assertThat(outcome.diagnostics().input()).isEqualTo(3);
    assertThat(outcome.diagnostics().output()).isEqualTo(2);
    assertThat(outcome.diagnostics().dropped()).isEqualTo(1);
    assertThat(outcome.diagnostics().fellBack()).isFalse();
  }

  @Test
  void incomingOrderIsPreservedWithinABucket() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(
      RerankLabel.ESSENTIAL, RerankLabel.RELATED, RerankLabel.ESSENTIAL, RerankLabel.RELATED));
    ModelReranker reranker = new ModelReranker(model);

    RerankOutcome outcome = reranker.rerank(
      input(List.of(candidate("a"), candidate("b"), candidate("c"), candidate("d"))));

    assertThat(ids(outcome)).containsExactly("a", "c", "b", "d");
  }

  @Test
  void scoreAndProvenanceSurviveTheStage() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.RELATED, RerankLabel.ESSENTIAL));
    ModelReranker reranker = new ModelReranker(model);

    RerankOutcome outcome = reranker.rerank(input(List.of(
      new RecallCandidate(mem("a"), 0.91, "exact-key"),
      new RecallCandidate(mem("b"), 0.42, "fts+vector"))));

    assertThat(outcome.candidates().getFirst().score()).isEqualTo(0.42);
    assertThat(outcome.candidates().getFirst().source()).isEqualTo("fts+vector");
    assertThat(outcome.candidates().getLast().source()).isEqualTo("exact-key");
  }

  @Test
  void unanimousIrrelevanceIsTreatedAsNoSignalNotAsAVerdict() {
    // A model that calls everything irrelevant is indistinguishable from a broken one, and
    // honouring it would silently blank a recall RRF had right.
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.IRRELEVANT, RerankLabel.IRRELEVANT));
    ModelReranker reranker = new ModelReranker(model);

    List<RecallCandidate> candidates = List.of(candidate("a"), candidate("b"));
    RerankOutcome outcome = reranker.rerank(input(candidates));

    assertThat(outcome.candidates()).isEqualTo(candidates);
    assertThat(outcome.diagnostics().fellBack()).isTrue();
    assertThat(outcome.diagnostics().dropped()).isZero();
  }

  @Test
  void misalignedLabelCountIsFailureNotPartialData() {
    // A label attached to the wrong candidate is a silent wrong drop, so a short list is refused
    // outright rather than applied to the prefix.
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.IRRELEVANT));
    ModelReranker reranker = new ModelReranker(model);

    List<RecallCandidate> candidates = List.of(candidate("a"), candidate("b"), candidate("c"));
    RerankOutcome outcome = reranker.rerank(input(candidates));

    assertThat(outcome.candidates()).isEqualTo(candidates);
    assertThat(outcome.diagnostics().fellBack()).isTrue();
  }

  @Test
  void emptyLabelsAreNoSignal() {
    FakeModelGateway model = new FakeModelGateway();
    ModelReranker reranker = new ModelReranker(model);

    List<RecallCandidate> candidates = List.of(candidate("a"), candidate("b"));
    RerankOutcome outcome = reranker.rerank(input(candidates));

    assertThat(outcome.candidates()).isEqualTo(candidates);
    assertThat(outcome.diagnostics().fellBack()).isTrue();
  }

  @Test
  void aThrowingModelDoesNotPropagate() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.ESSENTIAL, RerankLabel.IRRELEVANT));
    model.setUnavailable(true);
    ModelReranker reranker = new ModelReranker(model);

    List<RecallCandidate> candidates = List.of(candidate("a"), candidate("b"));
    RerankOutcome outcome = reranker.rerank(input(candidates));

    assertThat(outcome.candidates()).isEqualTo(candidates);
    assertThat(outcome.diagnostics().fellBack()).isTrue();
    assertThat(model.rerankCalls).isEqualTo(1);
  }

  @Test
  void disabledSwitchesSkipTheModelEntirely() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.ESSENTIAL, RerankLabel.IRRELEVANT));
    ModelReranker reranker = new ModelReranker(model);
    List<RecallCandidate> candidates = List.of(candidate("a"), candidate("b"));

    RerankSettings stageOff = new RerankSettings(false, 0.4, true, 30, 400, 4000L);
    RerankSettings modelOff = new RerankSettings(true, 0.4, false, 30, 400, 4000L);

    assertThat(reranker.rerank(new RerankInput("q", null, candidates, Map.of(), stageOff)).candidates())
      .isEqualTo(candidates);
    assertThat(reranker.rerank(new RerankInput("q", null, candidates, Map.of(), modelOff)).candidates())
      .isEqualTo(candidates);
    assertThat(model.rerankCalls).isZero();
  }

  @Test
  void candidateTextIsTruncatedToTheConfiguredBound() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.ESSENTIAL, RerankLabel.RELATED));
    ModelReranker reranker = new ModelReranker(model);

    Memory verbose = new Memory("v", "s1", MemoryType.FACT, "x".repeat(5000), "topic.v",
      null, false, "{}", null, T0);
    RerankSettings tight = new RerankSettings(true, 0.4, true, 30, 120, 4000L);

    reranker.rerank(new RerankInput("q", null,
      List.of(new RecallCandidate(verbose, 1.0, "fts"), candidate("b")), Map.of(), tight));

    assertThat(model.rerankedContents.getFirst().getFirst()).hasSize(120);
  }

  @Test
  void emptyAndSingletonInputsSkipTheModel() {
    FakeModelGateway model = new FakeModelGateway();
    ModelReranker reranker = new ModelReranker(model);

    assertThat(reranker.rerank(input(List.of())).candidates()).isEmpty();
    assertThat(reranker.rerank(input(List.of(candidate("a")))).candidates()).hasSize(1);
    assertThat(model.rerankCalls).isZero();
  }

}
