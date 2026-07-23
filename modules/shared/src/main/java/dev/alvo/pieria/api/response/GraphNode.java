package dev.alvo.pieria.api.response;

/**
 * One vertex in a graph-explorer response: a normalized entity with the two numbers the viewer
 * draws with.
 *
 * @param id      content-addressed entity id (stable across re-ingest)
 * @param type    normalized entity type: {@code person | project | tool | file | concept | ...}
 * @param name    normalized entity name
 * @param degree  number of active edges touching this entity <em>in the whole profile</em>, not
 *                just within the returned subgraph — so a node rendered with one visible edge can
 *                still advertise that it has forty more to expand into
 * @param hop     hops from the focus entity ({@code 0} for the focus, {@code -1} when the response
 *                has no focus, as in the overview)
 */
public record GraphNode(String id, String type, String name, int degree, int hop) {
}
