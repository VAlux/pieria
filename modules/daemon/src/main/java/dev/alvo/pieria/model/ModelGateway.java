package dev.alvo.pieria.model;

import dev.alvo.pieria.domain.Chunk;
import dev.alvo.pieria.domain.Classification;
import dev.alvo.pieria.domain.ExtractedCandidate;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.QueryAnalysis;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.domain.TemporalFact;
import dev.alvo.pieria.domain.VerificationResult;

import java.util.List;

/**
 * Provider-agnostic access to chat + embedding models, backed by Spring AI.
 * Two tiers: a small/fast model for structured stages and a large
 * model for synthesis only. Implementations that cannot reach the model must throw
 * {@link ModelUnavailableException} so the API can map it to 503.
 */
public interface ModelGateway {

  /**
   * Extract candidate memories: a single small-model call that returns candidates
   * (type + content, optional topic key/payload), to be verified and classified.
   */
  List<Memory> extractMemories(List<Message> messages);

  /**
   * Full-pass extraction: extract candidate memories from a single rendered
   * chunk transcript. Returns raw candidates (content + optional suggested type) for verification.
   */
  default List<ExtractedCandidate> extract(Chunk chunk) {
    throw new UnsupportedOperationException("extract(Chunk) not implemented");
  }

  /**
   * Detail-pass extraction: focus on concrete values (names, versions, prices,
   * paths, entity attributes, dates) that the broad full pass tends to miss.
   */
  default List<ExtractedCandidate> extractDetail(Chunk chunk) {
    throw new UnsupportedOperationException("extractDetail(Chunk) not implemented");
  }

  /**
   * Verification: check one extracted candidate against the source transcript,
   * returning a pass/correct/drop verdict (with corrected content when applicable).
   */
  default VerificationResult verify(ExtractedCandidate candidate, String transcript) {
    throw new UnsupportedOperationException("verify(...) not implemented");
  }

  /**
   * Classification and enrichment: assign a type, a normalized topic key for
   * keyed types, and 3-5 interrogative search queries for the given verified content.
   */
  default Classification classify(String content) {
    throw new UnsupportedOperationException("classify(...) not implemented");
  }

  /**
   * Recall query analysis: turn a raw recall query into ranked
   * candidate topic keys (for the exact-key channel), FTS keyword terms expanded with synonyms,
   * and a single HyDE declarative statement (a hypothetical one-sentence answer for the HyDE
   * vector channel). Runs on the small/fast model. Implementations must throw
   * {@link ModelUnavailableException} on provider failure; callers decide whether to fall back to
   * a deterministic analyzer.
   */
  default QueryAnalysis analyzeQuery(String query) {
    throw new UnsupportedOperationException("analyzeQuery(...) not implemented");
  }

  /**
   * Synthesize a natural-language answer to {@code query} from the top recall candidates (large model).
   */
  String synthesizeRecall(String query, List<RecallCandidate> candidates);

  /**
   * Synthesis: synthesize an answer from the fused candidates,
   * with pre-computed deterministic {@code temporalFacts} injected into the prompt (the model is
   * never asked to do date arithmetic). Implementations should require the answer to state when the
   * memory evidence is insufficient. The default ignores temporal facts and delegates to the
   * two-argument form so existing implementations keep working.
   */
  default String synthesizeRecall(String query, List<RecallCandidate> candidates,
                                  List<TemporalFact> temporalFacts) {
    return synthesizeRecall(query, candidates);
  }

  /**
   * Judge whether {@code actualAnswer} is semantically faithful to {@code expectedAnswer} for the
   * given {@code question}. The default falls back to case-insensitive exact match so test stubs
   * and CI gateways that don't implement this keep working without a live model.
   */
  default boolean judgeAnswerFaithfulness(String question, String expectedAnswer, String actualAnswer) {
    if (expectedAnswer == null && actualAnswer == null) return true;
    if (expectedAnswer == null || actualAnswer == null) return false;
    return expectedAnswer.strip().equalsIgnoreCase(actualAnswer.strip());
  }

  /**
   * Embed text for vector search. Defined now so config/contracts are stable.
   */
  float[] embed(String text);

  /**
   * Lightweight provider reachability probe for {@code /pieria-health}. Must NOT invoke a model or
   * generate tokens. The default returns {@code false} (configured but status unknown) so existing
   * test stubs ({@code FakeModelGateway}, {@code StubModelGateway}) compile without modification.
   * Production implementations should override with a cheap network check (e.g. HTTP HEAD on the
   * provider base URL). Never leak provider hostnames or secrets through the health response.
   */
  default boolean isModelProviderReachable() {
    return false;
  }

  /**
   * Report the model names the provider currently has available locally (e.g. the names returned by
   * Ollama's {@code /api/tags}). Used only for LOG-ONLY first-run guidance about which configured
   * models are missing; this method MUST NOT invoke a model, generate tokens, or trigger any
   * download. The default returns an empty set so existing stubs ({@code FakeModelGateway},
   * {@code StubModelGateway}) compile unchanged and callers degrade gracefully when the provider is
   * unreachable. Implementations must never throw; on any IO failure return an empty set.
   */
  default java.util.Set<String> availableModels() {
    return java.util.Set.of();
  }
}
