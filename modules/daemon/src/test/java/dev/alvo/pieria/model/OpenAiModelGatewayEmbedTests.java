package dev.alvo.pieria.model;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.model.provider.OllamaModelProviderAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Batch embedding on the real gateway. The whole point of {@code embedAll} is that N texts leave in
 * one request, so the mapping from request position back to vector is no longer implicit — it is
 * carried by {@link Embedding#getIndex()}. A vector attached to the wrong memory is silent retrieval
 * corruption with no signal that it happened, so both the realignment and the size check are pinned
 * here. Driven through a canned {@link EmbeddingModel}, so no live provider is needed.
 */
class OpenAiModelGatewayEmbedTests {

  /** Vector whose single component is {@code value}, so a misplaced vector is visible. */
  private static float[] vector(float value) {
    return new float[] {value};
  }

  /** Returns canned results for every call, recording the requested inputs. */
  private static final class CannedEmbeddingModel implements EmbeddingModel {
    private final List<Embedding> results;
    private final List<List<String>> requests = new ArrayList<>();

    CannedEmbeddingModel(List<Embedding> results) {
      this.results = results;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      requests.add(List.copyOf(request.getInstructions()));
      return new EmbeddingResponse(this.results);
    }

    @Override
    public float[] embed(Document document) {
      throw new UnsupportedOperationException("not used");
    }
  }

  private static OpenAiModelGateway gateway(EmbeddingModel embeddingModel) {
    PieriaProperties properties = new PieriaProperties(null, null, null,
      new PieriaProperties.Model("extract-model", "synth-model", "embed", 1024, 4, null, null),
      null, null, null);
    return new OpenAiModelGateway(null, null, embeddingModel, properties, new OllamaModelProviderAdapter());
  }

  @Test
  void embedAllSendsEveryTextInOneRequest() {
    CannedEmbeddingModel model = new CannedEmbeddingModel(List.of(
      new Embedding(vector(1.0f), 0),
      new Embedding(vector(2.0f), 1),
      new Embedding(vector(3.0f), 2)));

    List<float[]> vectors = gateway(model).embedAll(List.of("alpha", "beta", "gamma"));

    assertThat(model.requests).containsExactly(List.of("alpha", "beta", "gamma"));
    assertThat(vectors).hasSize(3);
    assertThat(vectors.get(0)).containsExactly(1.0f);
    assertThat(vectors.get(1)).containsExactly(2.0f);
    assertThat(vectors.get(2)).containsExactly(3.0f);
  }

  @Test
  void embedAllRealignsResultsByTheirReportedIndex() {
    // A provider is free to return results out of order; only the index is authoritative.
    CannedEmbeddingModel model = new CannedEmbeddingModel(List.of(
      new Embedding(vector(3.0f), 2),
      new Embedding(vector(1.0f), 0),
      new Embedding(vector(2.0f), 1)));

    List<float[]> vectors = gateway(model).embedAll(List.of("alpha", "beta", "gamma"));

    assertThat(vectors.get(0)).containsExactly(1.0f);
    assertThat(vectors.get(1)).containsExactly(2.0f);
    assertThat(vectors.get(2)).containsExactly(3.0f);
  }

  @Test
  void embedAllRejectsAResponseWithFewerVectorsThanTexts() {
    CannedEmbeddingModel model = new CannedEmbeddingModel(List.of(new Embedding(vector(1.0f), 0)));

    assertThatThrownBy(() -> gateway(model).embedAll(List.of("alpha", "beta")))
      .isInstanceOf(ModelUnavailableException.class)
      .hasMessageContaining("2")
      .hasMessageContaining("1");
  }

  @Test
  void embedAllOfAnEmptyListMakesNoProviderCall() {
    CannedEmbeddingModel model = new CannedEmbeddingModel(List.of());

    assertThat(gateway(model).embedAll(List.of())).isEmpty();
    assertThat(model.requests).isEmpty();
  }

  @Test
  void embedStillReturnsASingleVector() {
    CannedEmbeddingModel model = new CannedEmbeddingModel(List.of(new Embedding(vector(7.0f), 0)));

    assertThat(gateway(model).embed("alpha")).containsExactly(7.0f);
    assertThat(model.requests).containsExactly(List.of("alpha"));
  }
}
