package dev.alvo.pieria.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result of a recall: the synthesized answer and the memories used as evidence. When the
 * request opted into debug, an optional {@link RecallDebug} block carries candidate provenance,
 * the deterministic temporal facts, and per-channel diagnostics. The debug
 * block is omitted from the JSON when {@code null} so default responses stay concise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecallResponse(String answer, List<MemoryResponse> memories, RecallDebug debug) {

  /** Concise response (no debug block). */
  public RecallResponse(String answer, List<MemoryResponse> memories) {
    this(answer, memories, null);
  }

  /**
   * Debug payload returned only when {@code debug=true} was requested.
   *
   * @param candidates    fused candidates with RRF score + channel provenance, in rank order
   * @param temporalFacts pre-computed temporal facts injected into synthesis (rendered)
   * @param channels      per-channel latency/hit/failure diagnostics
   */
  public record RecallDebug(
    List<Provenance> candidates,
    List<String> temporalFacts,
    List<ChannelDiagnostic> channels) {

    /** One fused candidate's provenance: memory id, RRF score, and the channels that produced it. */
    public record Provenance(String id, double score, String source) {
    }

    /** Per-channel diagnostic: which channel, how long it took, how many hits, did it fail. */
    public record ChannelDiagnostic(String channel, long latencyMs, int hits, boolean failed) {
    }
  }
}
