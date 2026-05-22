package dev.alvo.pieria.model;

import dev.alvo.pieria.domain.Chunk;
import dev.alvo.pieria.domain.Classification;
import dev.alvo.pieria.domain.ExtractedCandidate;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.domain.VerificationResult;

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
   * Phase 2 full-pass extraction (SPEC 6.2): extract candidate memories from a single rendered
   * chunk transcript. Returns raw candidates (content + optional suggested type) for verification.
   */
  default List<ExtractedCandidate> extract(Chunk chunk) {
    throw new UnsupportedOperationException("extract(Chunk) not implemented");
  }

  /**
   * Phase 2 detail-pass extraction (SPEC 6.2): focus on concrete values (names, versions, prices,
   * paths, entity attributes, dates) that the broad full pass tends to miss.
   */
  default List<ExtractedCandidate> extractDetail(Chunk chunk) {
    throw new UnsupportedOperationException("extractDetail(Chunk) not implemented");
  }

  /**
   * Phase 2 verification (SPEC 6.3): check one extracted candidate against the source transcript,
   * returning a pass/correct/drop verdict (with corrected content when applicable).
   */
  default VerificationResult verify(ExtractedCandidate candidate, String transcript) {
    throw new UnsupportedOperationException("verify(...) not implemented");
  }

  /**
   * Phase 2 classification + enrichment (SPEC 6.4): assign a type, a normalized topic key for
   * keyed types, and 3-5 interrogative search queries for the given verified content.
   */
  default Classification classify(String content) {
    throw new UnsupportedOperationException("classify(...) not implemented");
  }

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
