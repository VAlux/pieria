package dev.alvo.pieria.model;

import dev.alvo.pieria.retrieval.model.RecallCandidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link ModelGateway#embedAll(List)} default: a gateway that only knows how to embed one text
 * at a time still satisfies the batch contract — one call per text, results index-aligned with the
 * inputs. That default is what keeps every existing test double compiling unchanged.
 */
class ModelGatewayEmbedAllTests {

  /** Minimal gateway: a one-element vector holding the text's length, recording every call. */
  private static final class SingleTextGateway implements ModelGateway {
    private final List<String> calls = new ArrayList<>();

    @Override
    public float[] embed(String text) {
      calls.add(text);
      return new float[] {text.length()};
    }

    @Override
    public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
      return "";
    }
  }

  @Test
  void defaultEmbedAllEmbedsEachTextInOrder() {
    SingleTextGateway gateway = new SingleTextGateway();

    List<float[]> vectors = gateway.embedAll(List.of("a", "bb", "ccc"));

    assertThat(gateway.calls).containsExactly("a", "bb", "ccc");
    assertThat(vectors).hasSize(3);
    assertThat(vectors.get(0)).containsExactly(1.0f);
    assertThat(vectors.get(1)).containsExactly(2.0f);
    assertThat(vectors.get(2)).containsExactly(3.0f);
  }

  @Test
  void defaultEmbedAllOfAnEmptyListMakesNoModelCall() {
    SingleTextGateway gateway = new SingleTextGateway();

    assertThat(gateway.embedAll(List.of())).isEmpty();
    assertThat(gateway.calls).isEmpty();
  }
}
