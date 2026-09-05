package dev.alvo.pieria.retrieval.rerank;

import dev.alvo.pieria.retrieval.model.RecallCandidate;

import java.util.List;
import java.util.Map;

/**
 * Everything a {@link Reranker} needs for one recall. Deliberately carries the already-fetched
 * {@code vectors} rather than a store handle: the near-duplicate collapse pass reads them anyway,
 * so passing the map through is what keeps the deterministic re-scorer free of I/O.
 *
 * @param query          the raw recall query
 * @param queryEmbedding the query's vector, or {@code null} when vector search is off or embedding
 *                       failed — which makes the semantic sub-stage a pass-through
 * @param candidates     the fused, collapsed candidates, in RRF order
 * @param vectors        memory id → stored embedding, for whichever candidates have one
 * @param settings       the tuning for this recall's profile
 */
public record RerankInput(String query,
                          float[] queryEmbedding,
                          List<RecallCandidate> candidates,
                          Map<String, float[]> vectors,
                          RerankSettings settings) {

  public RerankInput {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
    vectors = vectors == null ? Map.of() : Map.copyOf(vectors);
  }
}
