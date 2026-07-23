package dev.alvo.pieria.api.response;

import java.util.List;

/**
 * Result of {@code GET /v1/profiles/{name}/graph/overview}: the explorer's landing state.
 *
 * <p>Deliberately <em>not</em> the whole graph. {@code nodes} holds the profile's highest-degree
 * entities up to the requested cap, plus the edges induced among them; {@code entityCount} and
 * {@code edgeCount} report the true profile-wide totals so the viewer can say "showing 300 of
 * 27,786". A profile large enough to be interesting is always truncated here, by design.
 *
 * @param entityCount profile-wide count of entities touched by at least one active edge
 * @param edgeCount   profile-wide count of active edges
 * @param types       entity-type facet counts, highest first, for the type filter
 * @param nodes       the top-degree entities that fit under the cap
 * @param links       active edges with both endpoints among {@code nodes}
 * @param truncated   whether {@code nodes} omits entities that would otherwise qualify
 */
public record GraphOverviewResponse(
  int entityCount,
  int edgeCount,
  List<TypeFacet> types,
  List<GraphNode> nodes,
  List<GraphLink> links,
  boolean truncated) {

  /**
   * @param type  normalized entity type
   * @param count how many entities of that type the profile holds
   */
  public record TypeFacet(String type, int count) {
  }
}
