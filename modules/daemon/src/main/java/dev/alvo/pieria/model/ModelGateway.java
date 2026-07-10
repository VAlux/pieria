package dev.alvo.pieria.model;

import dev.alvo.pieria.ingestion.model.Chunk;
import dev.alvo.pieria.ingestion.model.Classification;
import dev.alvo.pieria.ingestion.model.UnifiedCandidate;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.retrieval.model.GraphEvidence;
import dev.alvo.pieria.retrieval.model.QueryAnalysis;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.TemporalFact;
import dev.alvo.pieria.ingestion.model.VerificationResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider-agnostic access to chat + embedding models, backed by Spring AI.
 * Two tiers: a small/fast model for structured stages and a large
 * model for synthesis only. Implementations that cannot reach the model must throw
 * {@link ModelUnavailableException} so the API can map it to 503.
 */
public interface ModelGateway {

  /**
   * Unified extraction: one small-model call per chunk that extracts candidate memories
   * <em>with</em> their classification (type, topic key, interrogative queries, payload) in a single
   * structured output — broad statements and concrete values alike. This is the only extraction
   * stage; candidates still go through verification, and a {@code CORRECT} verdict re-classifies
   * the corrected content via {@link #classify}.
   */
  default List<UnifiedCandidate> extractUnified(Chunk chunk) {
    throw new UnsupportedOperationException("extractUnified(Chunk) not implemented");
  }

  /**
   * Verification: check one extracted candidate's content against the source transcript,
   * returning a pass/correct/drop verdict (with corrected content when applicable).
   */
  default VerificationResult verify(String content, String transcript) {
    throw new UnsupportedOperationException("verify(...) not implemented");
  }

  /**
   * Batch verification: verify every candidate content of a single chunk against the shared source
   * {@code transcript} in one model call, returning verdicts aligned 1:1 with {@code contents} (same
   * order, same size). This sends the transcript once instead of re-sending it per candidate, which is
   * the dominant cost of the verify phase. The default delegates to per-content {@link #verify} so
   * stubs and gateways without batch support keep working; production implementations override with a
   * single batched call and must fall back to per-content verification on any batch parse failure.
   */
  default List<VerificationResult> verifyAll(List<String> contents, String transcript) {
    if (contents == null || contents.isEmpty()) {
      return List.of();
    }
    List<VerificationResult> results = new ArrayList<>(contents.size());
    for (String content : contents) {
      results.add(verify(content, transcript));
    }
    return results;
  }

  /**
   * Classification and enrichment: assign a type, a normalized topic key for
   * keyed types, and 3-5 interrogative search queries for the given verified content.
   */
  default Classification classify(String content) {
    throw new UnsupportedOperationException("classify(...) not implemented");
  }

  /**
   * Batch classification: classify several verified contents in one model call, returning results
   * aligned 1:1 with {@code contents} (same order, same size). The default delegates to per-content
   * {@link #classify} so stubs keep working; production implementations override with a single
   * batched call and must fall back to per-content classification on any batch parse failure.
   */
  default List<Classification> classifyAll(List<String> contents) {
    if (contents == null || contents.isEmpty()) {
      return List.of();
    }
    List<Classification> results = new ArrayList<>(contents.size());
    for (String content : contents) {
      results.add(classify(content));
    }
    return results;
  }

  /**
   * Graph extraction: from one verified memory's content, extract entities and
   * {@code (source, relation, target)} triples grounded in that content, for the relationship graph.
   * Runs on the small/fast model. This pass is additive and degradable — implementations should
   * return {@link GraphFragment#empty()} on empty/parse failure rather than throwing, and callers
   * must treat any failure as "store the memory without a graph". The default returns an empty
   * fragment so stubs/gateways without graph support keep working.
   */
  default GraphFragment extractGraph(String content) {
    return GraphFragment.empty();
  }

  /**
   * Batch graph extraction: extract a {@link GraphFragment} for several verified contents in one model
   * call, returning fragments aligned 1:1 with {@code contents} (same order, same size). Like
   * {@link #extractGraph} this pass is additive and degradable — the default catches per-content
   * failures and substitutes {@link GraphFragment#empty()} so one bad item never loses the others, and
   * production implementations override with a single batched call that falls back to per-content
   * extraction on a batch parse failure. Callers should still treat any empty fragment as
   * "store the memory without a graph".
   */
  default List<GraphFragment> extractGraphAll(List<String> contents) {
    if (contents == null || contents.isEmpty()) {
      return List.of();
    }
    List<GraphFragment> results = new ArrayList<>(contents.size());
    for (String content : contents) {
      try {
        results.add(extractGraph(content));
      } catch (RuntimeException e) {
        results.add(GraphFragment.empty());
      }
    }
    return results;
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
   * Synthesis with both the deterministic {@code temporalFacts} and the ephemeral code-graph
   * {@code graphEvidence} (rendered edge rows) injected into the prompt as ground truth. The
   * default ignores the evidence and delegates to the three-argument form so existing
   * implementations keep working.
   */
  default String synthesizeRecall(String query, List<RecallCandidate> candidates,
                                  List<TemporalFact> temporalFacts, List<GraphEvidence> graphEvidence) {
    return synthesizeRecall(query, candidates, temporalFacts);
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
   * The three cumulative levels of the code narrative layer (see {@code CodeSummarizationService}).
   */
  enum CodeSummaryLevel { FILE, MODULE, ARCHITECTURE }

  /**
   * Level-discriminated input for {@link #summarizeCode}; fields not meaningful for a level are
   * null/empty.
   *
   * @param level          which summary to write
   * @param subjectPath    file path (FILE), module path (MODULE), or repo/profile name (ARCHITECTURE)
   * @param language       source language (FILE only)
   * @param outlines       symbol-outline evidence: the file's outline (FILE), member-file outlines
   *                       (MODULE), or module member listings (ARCHITECTURE fallback)
   * @param childSummaries lower-level summaries feeding this one: file summaries (MODULE) or module
   *                       summaries (ARCHITECTURE); empty when the lower level is not generated
   * @param source         truncated file source text (FILE only)
   */
  record CodeSummaryInput(CodeSummaryLevel level,
                          String subjectPath,
                          String language,
                          List<String> outlines,
                          List<String> childSummaries,
                          String source) {

    public CodeSummaryInput {
      outlines = outlines == null ? List.of() : List.copyOf(outlines);
      childSummaries = childSummaries == null ? List.of() : List.copyOf(childSummaries);
    }
  }

  /**
   * Write one interpretive code-narrative summary (plain prose, no JSON) on the synthesis (large)
   * model. Implementations must throw {@link ModelUnavailableException} on provider failure.
   */
  default String summarizeCode(CodeSummaryInput input) {
    throw new UnsupportedOperationException("summarizeCode(...) not implemented");
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
   * Report the model names the provider currently has available (e.g. the {@code data[].id} values
   * returned by the OpenAI-compatible {@code /v1/models}). Used only for LOG-ONLY first-run guidance about which configured
   * models are missing; this method MUST NOT invoke a model, generate tokens, or trigger any
   * download. The default returns an empty set so existing stubs ({@code FakeModelGateway},
   * {@code StubModelGateway}) compile unchanged and callers degrade gracefully when the provider is
   * unreachable. Implementations must never throw; on any IO failure return an empty set.
   */
  default java.util.Set<String> availableModels() {
    return java.util.Set.of();
  }
}
