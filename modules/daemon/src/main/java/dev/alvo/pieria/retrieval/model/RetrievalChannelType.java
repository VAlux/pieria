package dev.alvo.pieria.retrieval.model;

/**
 * The parallel retrieval channels fused by RRF. Channel identity is carried on
 * every {@link RetrievalCandidate} so fusion can apply per-channel weights and diagnostics can
 * report per-channel hits.
 */
public enum RetrievalChannelType {
  /**
   * Porter-stemmed FTS over the active memory content.
   */
  FTS_MEMORY,
  /**
   * Direct map from the query's topic keys to known {@code topic_key}s (highest signal).
   */
  EXACT_KEY,
  /**
   * FTS over raw stored messages (lower-priority safety net).
   */
  FTS_MESSAGE,
  /**
   * Vector similarity using the embedded raw query.
   */
  DIRECT_VECTOR,
  /**
   * Vector similarity using the embedded HyDE hypothetical answer.
   */
  HYDE_VECTOR,
  /**
   * Entity-relation graph traversal seeded from query + wave-1 candidates (second wave).
   */
  GRAPH,
  /**
   * FTS over the code-symbol index, resolved to derived code memories (first wave).
   */
  SYMBOL_FTS,
  /**
   * Precise code-graph traversal over {@code code_edges}, resolved to code memories (second wave).
   */
  CODE_GRAPH
}
