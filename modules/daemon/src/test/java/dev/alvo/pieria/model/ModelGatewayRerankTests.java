package dev.alvo.pieria.model;

import dev.alvo.pieria.retrieval.model.RerankLabel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rerank method must be additive: a gateway that knows nothing about reranking still compiles
 * and still reports "no signal" rather than throwing.
 */
class ModelGatewayRerankTests {

  @Test
  void defaultImplementationReportsNoSignal() {
    ModelGateway bare = new ModelGateway() {
      @Override
      public String synthesizeRecall(String query, List<dev.alvo.pieria.retrieval.model.RecallCandidate> candidates) {
        return "";
      }

      @Override
      public float[] embed(String text) {
        return new float[0];
      }
    };

    assertThat(bare.rerankCandidates("q", List.of("a", "b"))).isEmpty();
  }

  @Test
  void fakeReturnsConfiguredLabelsAndRecordsTheCall() {
    FakeModelGateway fake = new FakeModelGateway();
    fake.setRerankLabels(List.of(RerankLabel.ESSENTIAL, RerankLabel.IRRELEVANT));

    List<RerankLabel> labels = fake.rerankCandidates("q", List.of("a", "b"));

    assertThat(labels).containsExactly(RerankLabel.ESSENTIAL, RerankLabel.IRRELEVANT);
    assertThat(fake.rerankCalls).isEqualTo(1);
    assertThat(fake.rerankedContents).containsExactly(List.of("a", "b"));
  }

  @Test
  void fakeReportsNoSignalUntilLabelsAreConfigured() {
    FakeModelGateway fake = new FakeModelGateway();

    assertThat(fake.rerankCandidates("q", List.of("a"))).isEmpty();
    assertThat(fake.rerankCalls).isEqualTo(1);
  }

  @Test
  void fakeHonoursUnavailable() {
    FakeModelGateway fake = new FakeModelGateway();
    fake.setRerankLabels(List.of(RerankLabel.ESSENTIAL));
    fake.setUnavailable(true);

    org.assertj.core.api.Assertions
      .assertThatThrownBy(() -> fake.rerankCandidates("q", List.of("a")))
      .isInstanceOf(ModelUnavailableException.class);
  }
}
