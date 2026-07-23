package dev.alvo.pieria.domain.graph;

/**
 * An {@link Entity} together with its active-edge degree (incoming plus outgoing). Degree is what
 * the explorer ranks by: it picks the profile's hubs for the overview and decides which neighbours
 * survive the node cap when a neighbourhood is larger than the viewer can usefully draw.
 */
public record RankedEntity(Entity entity, int degree) {
}
