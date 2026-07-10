package dev.alvo.pieria.config;

/**
 * How extracted candidates are verified against their source chunk
 * ({@code pieria.ingestion.verify-mode}).
 */
public enum VerifyMode {

  /** Every candidate goes to the model verifier (most model calls, strongest guard). */
  ALWAYS,

  /**
   * Default: candidates that pass the deterministic {@code GroundingFilter} are stored without a
   * model call; only suspect candidates go to the batched model verify.
   */
  GROUNDED,

  /** No verification at all: trust extraction (cheapest; loses the hallucination guard). */
  NEVER
}
