package dev.alvo.pieria.retrieval.rerank;

import dev.alvo.pieria.retrieval.RetrievalDiagnostics.RerankDiagnostics;
import dev.alvo.pieria.retrieval.model.RecallCandidate;

import java.util.List;

/**
 * What one rerank sub-stage produced: the (possibly reordered, possibly shortened) candidates and
 * the record of what it did.
 */
public record RerankOutcome(List<RecallCandidate> candidates, RerankDiagnostics diagnostics) {

  public RerankOutcome {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
  }

  /**
   * The stage declined to act and handed its input straight back. This is the shape of every
   * failure, every disabled path, and every absent signal — a reranker never has another way to
   * fail.
   */
  public static RerankOutcome passThrough(List<RecallCandidate> candidates, String stage, long latencyMs) {
    int size = candidates == null ? 0 : candidates.size();
    return new RerankOutcome(candidates, new RerankDiagnostics(stage, size, size, 0, latencyMs, true));
  }
}
