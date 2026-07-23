package dev.alvo.pieria.api.response;

/**
 * One directed, labelled edge in a graph-explorer response. Both endpoints are guaranteed to be
 * present in the same response's node list, so the viewer never has to draw a dangling stub.
 *
 * @param source   source entity id
 * @param target   target entity id
 * @param relation normalized relation label
 * @param memoryId provenance: the memory this edge was extracted from
 */
public record GraphLink(String source, String target, String relation, String memoryId) {
}
