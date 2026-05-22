package dev.alvo.pieria.model;

import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.RecallCandidate;

import java.util.List;

/**
 * Provider-agnostic access to chat + embedding models, backed by Spring AI.
 * Two tiers: a small/fast model for structured stages (Phase 1: naive extraction) and a large
 * model for synthesis only. Implementations that cannot reach the model must throw
 * {@link ModelUnavailableException} so the API can map it to 503.
 */
public interface ModelGateway {

  /**
   * Phase 1 naive extraction: a single small-model call that returns candidate memories
   * (type + content, optional topic key/payload). Phase 2 replaces this with the full
   * extract/verify/classify pipeline.
   */
  List<Memory> extractMemories(List<Message> messages);

  /**
   * Synthesize a natural-language answer to {@code query} from the top recall candidates (large model).
   */
  String synthesizeRecall(String query, List<RecallCandidate> candidates);

  /**
   * Embed text for vector search. Defined now so config/contracts are stable; Phase 1 recall
   * does not use it (no vector index until Phase 3).
   */
  float[] embed(String text);
}
