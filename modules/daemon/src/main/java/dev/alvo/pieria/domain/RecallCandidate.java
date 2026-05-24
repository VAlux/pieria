package dev.alvo.pieria.domain;

/**
 * A retrieval hit handed to synthesis. {@code score} carries the channel/fusion strength
 * (Phase 1 uses a simple lexical score; Phase 3 fills it from RRF). {@code source} records
 * which channel produced it, kept so the multi-channel design slots in without reshaping callers.
 */
public record RecallCandidate(Memory memory, double score, String source) {
}
