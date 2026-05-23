package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.domain.QueryAnalysis;

/**
 * Immutable inputs shared by every retrieval channel for a single recall (phase-3 steps 5-6).
 * The two embeddings are {@code null} when vector search is unavailable or embedding failed, in
 * which case the vector channels return no hits.
 *
 * @param profileId      resolved profile id
 * @param query          the original recall query (raw)
 * @param analysis       query analysis (topic keys, FTS terms, HyDE statement)
 * @param queryEmbedding embedding of the raw query for the direct vector channel, or {@code null}
 * @param hydeEmbedding  embedding of the HyDE statement for the HyDE vector channel, or {@code null}
 * @param limit          max hits each channel should return
 */
public record RetrievalContext(
  String profileId,
  String query,
  QueryAnalysis analysis,
  float[] queryEmbedding,
  float[] hydeEmbedding,
  int limit) {

  /** FTS match text: the analyzed terms when present, else the raw query (the store re-tokenizes). */
  public String ftsText() {
    if (analysis != null && !analysis.ftsTerms().isEmpty()) {
      return String.join(" ", analysis.ftsTerms());
    }
    return query;
  }
}
