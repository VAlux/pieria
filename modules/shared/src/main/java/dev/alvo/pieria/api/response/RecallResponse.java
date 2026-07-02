package dev.alvo.pieria.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result of a recall: the synthesized answer, the memories used as evidence, and any code-graph
 * evidence lines (precise symbol relations from the indexed source) that backed the answer. When
 * the request opted into debug, an optional {@link RecallDebug} block carries candidate provenance,
 * the deterministic temporal facts, and per-channel diagnostics. The codeEvidence and debug
 * blocks are omitted from the JSON when {@code null} so default responses stay concise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecallResponse(String answer, List<MemoryResponse> memories,
                             List<CodeEvidence> codeEvidence, RecallDebug debug) {

  /** Concise response (no code evidence, no debug block). */
  public RecallResponse(String answer, List<MemoryResponse> memories) {
    this(answer, memories, null, null);
  }

  /**
   * One code-graph fact used as synthesis evidence: {@code src --relation--> dst}.
   *
   * @param src        source symbol qualified name
   * @param srcPath    source symbol repo-relative path
   * @param relation   relation wire form, e.g. {@code "calls"}
   * @param dst        target qualified name, or the raw referenced name when unresolved
   * @param dstPath    target repo-relative path; null when the target is unresolved
   * @param confidence edge confidence wire form, e.g. {@code "resolved"}
   */
  public record CodeEvidence(String src, String srcPath, String relation,
                             String dst, String dstPath, String confidence) {
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
