package dev.alvo.pieria.domain.graph;

/**
 * One entity reached by a bounded breadth-first walk out from a focus entity, tagged with the
 * number of hops it took to get there ({@code 0} for the focus itself). The viewer uses the hop
 * distance to lay out and style rings around the focus.
 */
public record NeighborHop(String entityId, int hop) {
}
