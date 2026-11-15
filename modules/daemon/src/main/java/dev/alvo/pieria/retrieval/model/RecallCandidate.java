package dev.alvo.pieria.retrieval.model;

import dev.alvo.pieria.domain.memory.Memory;

/**
 * A retrieval hit handed to synthesis. {@code score} carries the channel/fusion strength.
 * {@code source} records which channel produced it, kept so the multi-channel design slots in
 * without reshaping callers. {@code ownerProfile} names the profile the memory belongs to; it is set
 * only when the recall read a profile together with the profiles below it, and is {@code null} for a
 * single-profile recall.
 *
 * <p><strong>List order, not {@code score}, is authoritative.</strong> {@code score} is always the
 * RRF score, deliberately: keeping it makes channel provenance interpretable and makes the
 * rerank-disabled path trivially comparable to the enabled one. But the rerank stage reorders
 * candidates without rewriting their scores, so a returned list is generally NOT sorted by
 * {@code score}. Rank candidates by their position; never re-sort them by this field.
 */
public record RecallCandidate(Memory memory, double score, String source, String ownerProfile) {

  public RecallCandidate(Memory memory, double score, String source) {
    this(memory, score, source, null);
  }

  /** This candidate attributed to {@code profile}. */
  public RecallCandidate ownedBy(String profile) {
    return new RecallCandidate(memory, score, source, profile);
  }
}
