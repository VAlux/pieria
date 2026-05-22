package dev.alvo.pieria.api.response;

import java.util.List;

/**
 * Result of a recall (SPEC 9.1): the synthesized answer and the memories used as evidence.
 */
public record RecallResponse(String answer, List<MemoryResponse> memories) {
}
