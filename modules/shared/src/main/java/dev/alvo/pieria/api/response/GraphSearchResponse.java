package dev.alvo.pieria.api.response;

import java.util.List;

/**
 * Result of {@code GET /v1/profiles/{name}/graph/search}: entities whose name matches the query,
 * most-connected first, so picking a search result lands the explorer somewhere with structure to
 * show. Matches carry {@code hop = -1} — they are candidates for a focus, not yet part of a walk.
 */
public record GraphSearchResponse(List<GraphNode> matches) {
}
