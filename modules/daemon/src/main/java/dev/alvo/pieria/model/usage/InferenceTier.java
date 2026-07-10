package dev.alvo.pieria.model.usage;

/**
 * The three model tiers Pieria spends inference tokens on. Spend is tracked per tier because the
 * tiers run different models at very different prices: the small/fast extraction model handles the
 * structured stages, the large synthesis model writes the recall answer, and the embedding model
 * vectorizes text.
 */
public enum InferenceTier {

  /** Small/fast structured stages: extract (unified), verify, classify, extractGraph, analyzeQuery. */
  EXTRACTION,

  /** Large model: synthesizeRecall (and the eval-only judgeAnswerFaithfulness). */
  SYNTHESIS,

  /** Embedding model. Note: some providers (Ollama) do not report embedding token usage. */
  EMBEDDING;

  /**
   * Map a gateway call {@code stage} (the label already passed to the usage log line) to its tier.
   * Unknown/null stages fall back to {@link #EXTRACTION}, the structured-pipeline default.
   */
  public static InferenceTier forStage(String stage) {
    if (stage == null) {
      return EXTRACTION;
    }
    return switch (stage) {
      case "synthesizeRecall", "judgeAnswerFaithfulness" -> SYNTHESIS;
      case "embed" -> EMBEDDING;
      default -> EXTRACTION;
    };
  }
}
