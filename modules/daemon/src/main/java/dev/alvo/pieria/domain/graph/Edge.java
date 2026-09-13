package dev.alvo.pieria.domain.graph;

import dev.alvo.pieria.domain.ContentId;

import java.time.Instant;

/**
 * A directed, labelled relationship between two {@link Entity} nodes, grounded in the memory it was
 * extracted from. The {@code id} is content-addressed over
 * {@code (profileId, sourceEntityId, relation, targetEntityId, memoryId)} so re-ingest is
 * idempotent (see {@link ContentId#forEdge}).
 *
 * <p>{@code memoryId} is the provenance seam: an edge is active only while its source memory is
 * visible in the recall's temporal snapshot and the attachment was already recorded. Edges are
 * retained across replacements; every hop applies the same temporal memory predicate. {@code id} and {@code createdAt}
 * are assigned at store time when null.
 */
public record Edge(
  String id,
  String profileId,
  String sourceEntityId,
  String targetEntityId,
  String relation,
  String memoryId,
  Instant createdAt) {
}
