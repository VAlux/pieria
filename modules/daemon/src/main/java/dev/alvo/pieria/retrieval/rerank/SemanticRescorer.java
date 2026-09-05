package dev.alvo.pieria.retrieval.rerank;

import dev.alvo.pieria.retrieval.RetrievalDiagnostics.RerankDiagnostics;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.tools.Vectors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Blends cosine-to-query into the fused RRF score, so candidates from channels with incomparable
 * native scales compete on one axis.
 *
 * <p>The problem this solves: an exact-key or FTS hit arrives with no semantic score at all — it
 * matched a string. RRF ranks it against vector hits by rank position alone, which says nothing
 * about whether it is *about* the query. One cosine per candidate, against vectors the collapse
 * pass has already read, gives every candidate a comparable relevance term.
 *
 * <p>Pure arithmetic: no store, no model, no clock. That is what lets it run in the EVIDENCE tier,
 * whose contract is the query embedding and nothing else.
 */
public class SemanticRescorer implements Reranker {

  static final String STAGE = "semantic";

  /**
   * A candidate with no stored vector keeps its normalized RRF score untouched.
   *
   * <p>{@code task} memories are deliberately never embedded, and a freshly ingested memory may
   * still be sitting in the vectorization outbox. Neither is evidence of irrelevance, so scoring
   * them 0 would make recall quality a function of indexer backlog rather than of the query.
   */
  private static double blend(double normalizedRrf, float[] queryEmbedding, float[] memoryVector,
                              double weight) {
    if (memoryVector == null || memoryVector.length != queryEmbedding.length) {
      return normalizedRrf;
    }
    // Clamped, not signed: an opposed vector must read as "no similarity", never as worse than a
    // candidate that has no vector to compare at all.
    double cosine = Math.max(0.0, Vectors.cosine(queryEmbedding, memoryVector));
    return (1.0 - weight) * normalizedRrf + weight * cosine;
  }

  @Override
  public RerankOutcome rerank(RerankInput input) {
    long start = System.nanoTime();
    List<RecallCandidate> candidates = input.candidates();
    RerankSettings settings = input.settings();

    if (!settings.enabled() || settings.semanticWeight() <= 0.0
      || input.queryEmbedding() == null || candidates.size() < 2) {
      return RerankOutcome.passThrough(candidates, STAGE, elapsedMillis(start));
    }

    Map<String, float[]> vectors = input.vectors();
    double weight = settings.semanticWeight();
    float[] query = input.queryEmbedding();

    double min = candidates.stream().mapToDouble(RecallCandidate::score).min().orElse(0.0);
    double max = candidates.stream().mapToDouble(RecallCandidate::score).max().orElse(0.0);
    double span = max - min;

    List<Scored> scored = new ArrayList<>(candidates.size());
    for (int i = 0; i < candidates.size(); i++) {
      RecallCandidate candidate = candidates.get(i);
      // An all-equal window has no spread to normalize; treating every candidate as 1.0 keeps the
      // cosine term as the only discriminator rather than dividing by zero.
      double normalized = span <= 0.0 ? 1.0 : (candidate.score() - min) / span;
      double blended = blend(normalized, query, vectors.get(candidate.memory().id()), weight);
      scored.add(new Scored(candidate, blended, i));
    }

    // Incoming order breaks ties, which is what carries fusion's deterministic recency-then-id
    // tie-break through this stage rather than re-deriving it.
    scored.sort(Comparator.comparingDouble(Scored::score).reversed()
      .thenComparingInt(Scored::originalIndex));

    List<RecallCandidate> reordered = scored.stream().map(Scored::candidate).toList();
    int size = reordered.size();
    return new RerankOutcome(reordered,
      new RerankDiagnostics(STAGE, candidates.size(), size, 0, elapsedMillis(start), false));
  }

  private static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  private record Scored(RecallCandidate candidate, double score, int originalIndex) {
  }
}
