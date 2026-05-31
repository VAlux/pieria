package dev.alvo.pieria.retrieval.model;

import dev.alvo.pieria.domain.memory.Memory;

/**
 * A retrieval hit handed to synthesis. {@code score} carries the channel/fusion strength.
 * {@code source} records which channel produced it, kept so the multi-channel design slots in
 * without reshaping callers.
 */
public record RecallCandidate(Memory memory, double score, String source) {
}
