package dev.alvo.pieria.domain.graph;

import java.util.List;

/**
 * A read-only, profile-scoped view of the entity-relation graph for visualization: the connected
 * set of {@link Entity} nodes together with the active edges between them. Only edges whose source
 * memory is active ({@code superseded = 0}) are included, and only entities that are an endpoint of
 * at least one such edge — isolated entities carry no relationships to draw and are omitted.
 *
 * <p>Each {@link Link} carries the {@code memoryId} of the memory it was extracted from plus a short
 * snippet of that memory's content, so the viewer can show provenance on hover without a second
 * round-trip.
 */
public record GraphSnapshot(List<Entity> nodes, List<Link> links) {

  public record Link(
    String sourceEntityId,
    String targetEntityId,
    String relation,
    String memoryId,
    String memoryContent) {
  }

  public static GraphSnapshot empty() {
    return new GraphSnapshot(List.of(), List.of());
  }
}
