package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.retrieval.model.QueryAnalysis;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;

import java.util.List;

/**
 * Per-recall retrieval diagnostics: one entry per channel with its latency, hit
 * count, and whether it failed, plus the analysis that drove the channels, plus one entry per
 * rerank sub-stage that ran. Collected only when the caller asks for debug output; default API
 * responses stay concise.
 *
 * @param analysis the query analysis used for this recall
 * @param channels per-channel timing/hit/failure records
 * @param rerank   per-rerank-stage records, in the order the stages ran
 */
public record RetrievalDiagnostics(QueryAnalysis analysis,
                                   List<ChannelDiagnostics> channels,
                                   List<RerankDiagnostics> rerank) {

  public RetrievalDiagnostics {
    channels = channels == null ? List.of() : List.copyOf(channels);
    rerank = rerank == null ? List.of() : List.copyOf(rerank);
  }

  /**
   * @param channel   the channel
   * @param latencyMs wall-clock time the channel took
   * @param hits      number of candidates it returned
   * @param failed    whether it failed/timed out (only possible for non-critical channels)
   */
  public record ChannelDiagnostics(RetrievalChannelType channel, long latencyMs, int hits, boolean failed) {
  }

  /**
   * One rerank sub-stage's outcome.
   *
   * <p>{@code fellBack} is the field to read when a rerank looks like it did nothing: it means the
   * stage declined to act — disabled, no query embedding, no model signal, a timeout, misaligned
   * labels, or unanimous irrelevance — and returned its input untouched. That is the designed
   * behaviour, not an error, but it is invisible from the candidate list alone.
   *
   * @param stage     which sub-stage: {@code "semantic"} or {@code "model"}
   * @param input     candidates handed to the stage
   * @param output    candidates it returned
   * @param dropped   candidates it removed ({@code input - output})
   * @param latencyMs wall-clock time the stage took
   * @param fellBack  whether the stage passed its input through unchanged
   */
  public record RerankDiagnostics(String stage, int input, int output, int dropped,
                                  long latencyMs, boolean fellBack) {
  }
}
