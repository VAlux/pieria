package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.retrieval.model.GraphEvidence;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;

import java.util.ArrayList;
import java.util.List;

/**
 * One of the five parallel retrieval strategies. Each channel turns the
 * shared {@link RetrievalContext} into a ranked list of {@link RetrievalCandidate}s carrying its
 * own {@link RetrievalChannelType}; fusion ({@link ReciprocalRankFusion}) combines them downstream.
 *
 * <p>Channels are plain stateless wrappers over {@code MemoryStore}; {@link RetrievalService}
 * constructs the set and runs them concurrently.
 */
public interface RetrievalChannel {

  /**
   * Wrap a store result (already ranked best-first) into {@link RetrievalCandidate}s with 1-based
   * ranks and the given channel type. The matched content is kept as the diagnostic snippet.
   */
  static List<RetrievalCandidate> ranked(List<Memory> memories, RetrievalChannelType type) {
    List<RetrievalCandidate> retrievalCandidates = new ArrayList<>(memories.size());
    for (int i = 0; i < memories.size(); i++) {
      Memory m = memories.get(i);
      retrievalCandidates.add(new RetrievalCandidate(m, type, i + 1, m.content()));
    }

    return retrievalCandidates;
  }

  RetrievalChannelType type();

  /**
   * Whether a failure of this channel should fail the whole recall. Local-storage channels (FTS,
   * exact key) are {@code critical}; the vector channels are best-effort so recall degrades
   * gracefully to FTS + keyed lookup when the embedding/vector index is unavailable
   * (acceptance criterion).
   */
  default boolean critical() {
    return true;
  }

  List<RetrievalCandidate> retrieve(RetrievalContext ctx);

  /**
   * Full channel output: the fused-rankable memory candidates plus any {@link GraphEvidence} lines
   * that bypass fusion and go straight to synthesis (edges are not memories, so they cannot be
   * rank-aggregated). The default wraps {@link #retrieve} with no evidence; only the code-graph
   * channel overrides it.
   */
  default ChannelResult retrieveWithEvidence(RetrievalContext ctx) {
    return ChannelResult.of(retrieve(ctx));
  }

  /**
   * A channel's candidates and its non-fused code-graph evidence.
   */
  record ChannelResult(List<RetrievalCandidate> candidates, List<GraphEvidence> evidence) {
    public static ChannelResult of(List<RetrievalCandidate> candidates) {
      return new ChannelResult(candidates, List.of());
    }
  }
}
