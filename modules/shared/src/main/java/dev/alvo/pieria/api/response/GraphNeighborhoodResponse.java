package dev.alvo.pieria.api.response;

import java.util.List;

/**
 * Result of {@code GET /v1/profiles/{name}/graph/neighborhood}: the subgraph around one focus
 * entity, bounded by hop depth and a node cap.
 *
 * <p>When the focus has more neighbours than the cap allows, the highest-degree ones are kept and
 * {@code truncated} is set — {@code totalNeighbors} then reports how many there really were, so the
 * viewer can say "showing 300 of 370" rather than silently lying about the shape of the graph.
 *
 * @param focusId        the entity the walk started from; present in {@code nodes} at hop 0
 * @param nodes          focus plus everything reached, each tagged with its hop distance
 * @param links          active edges with both endpoints among {@code nodes}
 * @param truncated      whether neighbours were dropped to fit the cap
 * @param totalNeighbors how many entities the unbounded walk would have reached
 */
public record GraphNeighborhoodResponse(
  String focusId,
  List<GraphNode> nodes,
  List<GraphLink> links,
  boolean truncated,
  int totalNeighbors) {
}
