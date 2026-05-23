package dev.alvo.pieria.domain;

/**
 * The five parallel retrieval channels fused by RRF (SPEC 7.1). Channel identity is carried on
 * every {@link RetrievalCandidate} so fusion can apply per-channel weights and diagnostics can
 * report per-channel hits.
 */
public enum RetrievalChannelType {
  /** Porter-stemmed FTS over the active memory content. */
  FTS_MEMORY,
  /** Direct map from the query's topic keys to known {@code topic_key}s (highest signal). */
  EXACT_KEY,
  /** FTS over raw stored messages (lower-priority safety net). */
  FTS_MESSAGE,
  /** Vector similarity using the embedded raw query. */
  DIRECT_VECTOR,
  /** Vector similarity using the embedded HyDE hypothetical answer. */
  HYDE_VECTOR
}
