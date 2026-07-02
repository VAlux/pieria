package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.retrieval.model.GraphEvidence;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.TemporalFact;

import java.util.List;

/**
 * Outcome of a recall (steps 7-10): the synthesized answer, the fused candidates that were
 * used as evidence (carrying RRF score + channel provenance), the deterministic temporal facts
 * injected into synthesis, the ephemeral code-graph evidence lines, and optional per-channel
 * diagnostics (present only when debug was requested). Keeps the controller decoupled from the
 * multi-channel retrieval internals.
 *
 * @param answer        synthesized natural-language answer
 * @param candidates    fused, ranked candidates (RRF score + source)
 * @param temporalFacts pre-computed temporal facts handed to synthesis
 * @param graphEvidence code-graph edge evidence handed to synthesis (never fused, never stored)
 * @param diagnostics   per-channel diagnostics, or {@code null} when not requested
 */
public record RecallResult(
  String answer,
  List<RecallCandidate> candidates,
  List<TemporalFact> temporalFacts,
  List<GraphEvidence> graphEvidence,
  RetrievalDiagnostics diagnostics) {

  public RecallResult {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
    temporalFacts = temporalFacts == null ? List.of() : List.copyOf(temporalFacts);
    graphEvidence = graphEvidence == null ? List.of() : List.copyOf(graphEvidence);
  }

  /** The evidence memories in fused rank order (convenience for the concise API response). */
  public List<Memory> memories() {
    return candidates.stream().map(RecallCandidate::memory).toList();
  }
}
