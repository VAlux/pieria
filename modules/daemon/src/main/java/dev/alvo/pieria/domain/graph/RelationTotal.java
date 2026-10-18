package dev.alvo.pieria.domain.graph;

/**
 * How many active edges of one relation touch an entity on one side: {@code outgoing} when the
 * entity is the edge's source. Unlike the capped relation list, these counts cover every edge.
 */
public record RelationTotal(String relation, boolean outgoing, long count) {
}
