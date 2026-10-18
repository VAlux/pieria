package dev.alvo.pieria.retrieval;

/**
 * Wall-clock milliseconds per recall stage. {@code channelsMs} covers both retrieval waves;
 * {@code rerankMs} is the part of {@code fusionMs} spent reranking.
 */
public record RecallTimings(long analysisMs, long embeddingMs, long channelsMs, long fusionMs, long rerankMs,
                            long temporalMs, long synthesisMs, long totalMs) {

  /** Query analysis, embedding and the channel fan-out: everything before fusion. */
  public long retrieveMs() {
    return analysisMs + embeddingMs + channelsMs;
  }

  /** Temporal fact extraction plus the synthesis call. */
  public long synthesizeMs() {
    return temporalMs + synthesisMs;
  }
}
