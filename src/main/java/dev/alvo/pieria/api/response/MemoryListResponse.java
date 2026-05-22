package dev.alvo.pieria.api.response;

import java.util.List;

/**
 * Result of GET /v1/profiles/{name}/memories.
 */
public record MemoryListResponse(List<MemoryResponse> memories) {
}
