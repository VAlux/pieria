package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.retrieval.model.QueryAnalysis;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;

import java.util.List;

/**
 * Immutable inputs shared by every retrieval channel for a single recall (steps 5-6).
 * The two embeddings are {@code null} when vector search is unavailable or embedding failed, in
 * which case the vector channels return no hits.
 *
 * <p>{@code seedCandidates} are the hits surfaced by the first wave (the five primary channels);
 * they are empty for the primary wave and populated only for the second (graph) wave so the graph
 * channel can seed its traversal from what the other channels already found.
 *
 * @param profileId      resolved profile id
 * @param query          the original recall query (raw)
 * @param analysis       query analysis (topic keys, FTS terms, entities, HyDE statement)
 * @param queryEmbedding embedding of the raw query for the direct vector channel, or {@code null}
 * @param hydeEmbedding  embedding of the HyDE statement for the HyDE vector channel, or {@code null}
 * @param limit          max hits each channel should return
 * @param seedCandidates first-wave hits used to seed the graph channel (empty for the first wave)
 */
public record RetrievalContext(
  String profileId,
  String query,
  QueryAnalysis analysis,
  float[] queryEmbedding,
  float[] hydeEmbedding,
  int limit,
  List<RetrievalCandidate> seedCandidates) {

  public RetrievalContext {
    seedCandidates = seedCandidates == null ? List.of() : List.copyOf(seedCandidates);
  }

  /**
   * Primary-wave context: no graph seeds yet.
   */
  public RetrievalContext(String profileId, String query, QueryAnalysis analysis,
                          float[] queryEmbedding, float[] hydeEmbedding, int limit) {
    this(profileId, query, analysis, queryEmbedding, hydeEmbedding, limit, List.of());
  }

  /**
   * A copy of this context carrying the given first-wave hits as graph seeds.
   */
  public RetrievalContext withSeedCandidates(List<RetrievalCandidate> seeds) {
    return new RetrievalContext(profileId, query, analysis, queryEmbedding, hydeEmbedding, limit, seeds);
  }

  /**
   * FTS match text: the analyzed terms when present, else the raw query (the store re-tokenizes).
   */
  public String ftsText() {
    if (analysis != null && !analysis.ftsTerms().isEmpty()) {
      return String.join(" ", analysis.ftsTerms());
    }
    return query;
  }
}
