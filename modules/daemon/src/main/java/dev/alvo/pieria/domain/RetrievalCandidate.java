package dev.alvo.pieria.domain;

/**
 * One hit produced by a single retrieval channel before fusion (phase-3 steps 5-7). Carries the
 * matched {@link Memory}, which {@link RetrievalChannelType} produced it, the 1-based rank within
 * that channel (the input to Reciprocal Rank Fusion), and an optional source snippet for diagnostics.
 *
 * <p>Distinct from {@link RecallCandidate}: a {@code RetrievalCandidate} is a per-channel, pre-fusion
 * hit; {@code RecallCandidate} is the post-fusion, synthesis-facing result carrying the final score.
 *
 * @param memory        the matched active memory
 * @param channel       the channel that produced this hit
 * @param rankInChannel 1-based position within the channel's ranked results
 * @param snippet       optional matched text snippet (may be {@code null})
 */
public record RetrievalCandidate(
  Memory memory,
  RetrievalChannelType channel,
  int rankInChannel,
  String snippet) {
}
