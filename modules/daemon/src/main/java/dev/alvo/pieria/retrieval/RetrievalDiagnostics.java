package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.retrieval.model.QueryAnalysis;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;

import java.util.List;

/**
 * Per-recall retrieval diagnostics: one entry per channel with its latency, hit
 * count, and whether it failed, plus the analysis that drove the channels. Collected only when the
 * caller asks for debug output; default API responses stay concise.
 *
 * @param analysis the query analysis used for this recall
 * @param channels per-channel timing/hit/failure records
 */
public record RetrievalDiagnostics(QueryAnalysis analysis, List<ChannelDiagnostics> channels) {

  public RetrievalDiagnostics {
    channels = channels == null ? List.of() : List.copyOf(channels);
  }

  /**
   * @param channel   the channel
   * @param latencyMs wall-clock time the channel took
   * @param hits      number of candidates it returned
   * @param failed    whether it failed/timed out (only possible for non-critical channels)
   */
  public record ChannelDiagnostics(RetrievalChannelType channel, long latencyMs, int hits, boolean failed) {
  }
}
