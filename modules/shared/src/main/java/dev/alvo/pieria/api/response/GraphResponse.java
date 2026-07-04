package dev.alvo.pieria.api.response;

import java.util.List;

/**
 * Result of {@code GET /v1/profiles/{name}/graph}: the profile's entity-relation graph as a flat
 * node/link list ready for a force-directed viewer. Only connected entities and edges off active
 * (non-superseded) memories are included.
 *
 * @param nodes graph vertices — normalized entities
 * @param links directed, labelled edges between node ids, each tagged with its provenance memory
 */
public record GraphResponse(List<Node> nodes, List<Link> links) {

  /**
   * @param id   content-addressed entity id (stable across re-ingest)
   * @param type normalized entity type: {@code person | project | tool | file | concept | ...}
   * @param name normalized entity name
   */
  public record Node(String id, String type, String name) {
  }

  /**
   * @param source   source entity id (matches a {@link Node#id})
   * @param target   target entity id (matches a {@link Node#id})
   * @param relation normalized relation label
   * @param memoryId provenance: id of the memory this edge was extracted from
   * @param memory   short snippet of that memory's content, for hover provenance
   */
  public record Link(String source, String target, String relation, String memoryId, String memory) {
  }
}
