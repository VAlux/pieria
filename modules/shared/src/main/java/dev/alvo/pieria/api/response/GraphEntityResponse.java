package dev.alvo.pieria.api.response;

import java.util.List;

/**
 * Result of {@code GET /v1/profiles/{name}/graph/entities/{id}}: everything the explorer's
 * inspector shows for one entity — what it is, what it is connected to, and which memories put it
 * there.
 *
 * <p>{@code relations} is the entity's <em>full</em> active relation list (up to a cap), not just
 * the edges currently drawn on the canvas, so the inspector can reveal connections the bounded
 * subgraph left out. {@code memories} reuses {@link MemoryResponse} unchanged so the console can
 * hand one straight to the same detail drawer the Memories tab uses.
 *
 * @param entity    the entity itself, with its profile-wide degree
 * @param relations active edges touching it, newest first
 * @param memories  the active memories those edges were extracted from
 */
public record GraphEntityResponse(
  GraphNode entity,
  List<Relation> relations,
  List<MemoryResponse> memories) {

  /**
   * One edge as the inspector reads it: always "<em>this entity</em> {@code relation}
   * {@code otherName}", with {@code direction} recording which way round the underlying edge runs.
   *
   * @param direction {@code "out"} when the inspected entity is the edge's source, {@code "in"}
   *                  when it is the target
   * @param relation  normalized relation label
   * @param otherId   entity id at the far end — the viewer re-focuses on this
   * @param otherName normalized name at the far end
   * @param otherType normalized type at the far end
   * @param memoryId  provenance memory for this edge
   */
  public record Relation(
    String direction,
    String relation,
    String otherId,
    String otherName,
    String otherType,
    String memoryId) {
  }
}
