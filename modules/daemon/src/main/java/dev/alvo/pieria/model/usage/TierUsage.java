package dev.alvo.pieria.model.usage;

/**
 * Accumulated real provider token usage for a single {@link InferenceTier}: how many model calls
 * were made and the prompt/completion tokens they reported.
 *
 * @param calls            number of model calls recorded for the tier
 * @param promptTokens     Σ provider-reported prompt (input) tokens
 * @param completionTokens Σ provider-reported completion (output) tokens
 */
public record TierUsage(long calls, long promptTokens, long completionTokens) {

  /**
   * Total tokens (prompt + completion); not stored, derived on demand.
   */
  public long totalTokens() {
    return promptTokens + completionTokens;
  }
}
