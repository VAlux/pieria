package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.domain.Memory;

import java.util.List;

/**
 * Outcome of a recall: the synthesized natural-language answer plus the active memories that
 * were used as evidence. Keeps the controller decoupled from the multi-channel retrieval
 * internals (phase doc step 7).
 */
public record RecallResult(String answer, List<Memory> memories) {
}
