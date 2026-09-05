package dev.alvo.pieria.retrieval.rerank;

/**
 * The rerank stage's tuning, lifted out of {@code PieriaProperties.Retrieval} so this package does
 * not depend on Spring configuration binding and stays unit-testable with plain values.
 *
 * @param enabled        master switch over BOTH sub-stages; false means the pipeline behaves
 *                       exactly as it did before the stage existed
 * @param semanticWeight the cosine term's share of the blended score, in [0,1]; 0 disables the
 *                       deterministic re-scorer alone
 * @param modelEnabled   whether the model reranker runs (it additionally requires a recall tier
 *                       that already does model analysis)
 * @param window         how many fused candidates the stage considers
 * @param snippetChars   per-candidate character bound on the text handed to the model
 * @param timeoutMs      wall-clock bound on the model call
 */
public record RerankSettings(boolean enabled,
                             double semanticWeight,
                             boolean modelEnabled,
                             int window,
                             int snippetChars,
                             long timeoutMs) {

  public RerankSettings {
    semanticWeight = Math.clamp(semanticWeight, 0.0, 1.0);
    window = Math.max(0, window);
    snippetChars = Math.max(1, snippetChars);
    timeoutMs = Math.max(1L, timeoutMs);
  }
}
