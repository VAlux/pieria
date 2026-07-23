package dev.alvo.pieria.domain.graph;

/**
 * An active edge touching some entity of interest, resolved together with the entity at its far
 * end. {@code outgoing} says which side the entity of interest sat on: {@code true} when it is the
 * edge's source (so {@code other} is the target), {@code false} when it is the target.
 *
 * <p>This is the inspector's unit of display — "<em>this entity</em> {@code relation} {@code other}"
 * — so resolving {@code other} in the same query avoids a second round-trip per row.
 */
public record IncidentEdge(Edge edge, Entity other, boolean outgoing) {
}
