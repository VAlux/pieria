package dev.alvo.pieria.domain.graph;

/**
 * Profile-wide totals for the graph explorer's status line: how many entities are connected by at
 * least one active edge, and how many active edges there are. "Active" means the edge's provenance
 * memory is not superseded — the same predicate every other graph read applies.
 */
public record GraphCounts(int entityCount, int edgeCount) {

  public static GraphCounts empty() {
    return new GraphCounts(0, 0);
  }
}
