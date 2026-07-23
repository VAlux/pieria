package dev.alvo.pieria.graph;

import dev.alvo.pieria.api.response.GraphEntityResponse;
import dev.alvo.pieria.api.response.GraphLink;
import dev.alvo.pieria.api.response.GraphNeighborhoodResponse;
import dev.alvo.pieria.api.response.GraphNode;
import dev.alvo.pieria.api.response.GraphOverviewResponse;
import dev.alvo.pieria.api.response.GraphSearchResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.domain.graph.Edge;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.graph.GraphCounts;
import dev.alvo.pieria.domain.graph.IncidentEdge;
import dev.alvo.pieria.domain.graph.NeighborHop;
import dev.alvo.pieria.domain.graph.RankedEntity;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The read side of the console's graph explorer.
 *
 * <p>The explorer never renders a whole profile. A real profile runs to tens of thousands of
 * entities and edges — far past what a force layout can place or a human can read — so every
 * response here is a deliberately bounded slice: the profile's hubs, or a few hops around one focus
 * entity. This service owns the bounding: which slice, how big, ranked how, and whether the caller
 * is being told the truth about what was left out.
 *
 * <p>Selection is by degree throughout. When a slice overflows its cap the highest-degree entities
 * survive, on the grounds that a hub explains more of the surrounding structure than a leaf does.
 */
@Service
public class GraphExplorerService {

  /** Nodes returned when the caller does not ask for a specific cap. */
  static final int DEFAULT_NODE_LIMIT = 300;

  /** Hard ceiling on nodes per response, whatever the caller asks for. */
  static final int MAX_NODE_LIMIT = 1000;

  /** Matches returned by a search when the caller does not ask for a specific cap. */
  static final int DEFAULT_SEARCH_LIMIT = 20;

  static final int MAX_SEARCH_LIMIT = 100;

  /** Hops the neighbourhood walk will go out, at most. */
  static final int MAX_DEPTH = 3;

  /** Relations listed in the inspector for one entity. */
  static final int RELATION_LIMIT = 200;

  /** Provenance memories listed in the inspector for one entity. */
  static final int MEMORY_LIMIT = 25;

  /** Hop value for nodes that are not part of a focused walk (overview and search results). */
  private static final int NO_HOP = -1;

  private final MemoryStore store;
  private final Converter<Memory, MemoryResponse> memoryResponseConverter;

  public GraphExplorerService(MemoryStore store,
                              Converter<Memory, MemoryResponse> memoryResponseConverter) {
    this.store = store;
    this.memoryResponseConverter = memoryResponseConverter;
  }

  /**
   * The landing view: profile totals, the type facet, and the top-degree entities with the edges
   * induced among them.
   */
  public GraphOverviewResponse overview(String profileName, List<String> types, Integer limit) {
    Profile profile = findOrThrow(profileName);
    int cap = nodeLimit(limit);

    GraphCounts counts = store.graphCounts(profile.id());
    List<GraphOverviewResponse.TypeFacet> facets = store.entityTypeCounts(profile.id()).entrySet().stream()
      .map(e -> new GraphOverviewResponse.TypeFacet(e.getKey(), e.getValue()))
      .toList();

    // Over-fetch by one so a full page can be distinguished from an exactly-full one.
    List<RankedEntity> hubs = store.topEntitiesByDegree(profile.id(), safe(types), cap + 1);
    boolean truncated = hubs.size() > cap;
    if (truncated) {
      hubs = hubs.subList(0, cap);
    }

    List<GraphNode> nodes = hubs.stream()
      .map(r -> node(r.entity(), r.degree(), NO_HOP))
      .toList();

    return new GraphOverviewResponse(
      counts.entityCount(),
      counts.edgeCount(),
      facets,
      nodes,
      linksAmong(profile.id(), nodes),
      truncated);
  }

  /** Entity name search, most-connected first, for the explorer's search box. */
  public GraphSearchResponse search(String profileName, String query, List<String> types, Integer limit) {
    Profile profile = findOrThrow(profileName);
    int cap = bounded(limit, DEFAULT_SEARCH_LIMIT, MAX_SEARCH_LIMIT);

    List<GraphNode> matches = store.searchEntities(profile.id(), query, safe(types), cap).stream()
      .map(r -> node(r.entity(), r.degree(), NO_HOP))
      .toList();
    return new GraphSearchResponse(matches);
  }

  /**
   * The focused view: a bounded walk out from {@code entityId}, plus the edges induced among
   * whatever survived the cap.
   */
  public GraphNeighborhoodResponse neighborhood(String profileName, String entityId, Integer depth,
                                                List<String> types, Integer limit) {
    Profile profile = findOrThrow(profileName);
    int cap = nodeLimit(limit);
    int hops = bounded(depth, 1, MAX_DEPTH);

    // Walk wider than the cap so the degree ranking below has candidates to choose between; without
    // the slack the cap would just keep whichever neighbours the BFS happened to reach first.
    List<NeighborHop> reached = store.graphNeighborhood(
      profile.id(), entityId, hops, safe(types), Math.min(cap * 2, MAX_NODE_LIMIT * 2));

    Map<String, Integer> hopByEntity = new LinkedHashMap<>();
    reached.forEach(n -> hopByEntity.putIfAbsent(n.entityId(), n.hop()));

    List<Entity> entities = store.findEntitiesByIds(profile.id(), List.copyOf(hopByEntity.keySet()));
    if (entities.isEmpty()) {
      throw NotFoundException.entity(entityId);
    }
    Map<String, Integer> degrees = store.entityDegrees(profile.id(), List.copyOf(hopByEntity.keySet()));

    // The focus always survives the cap; everything else competes on hop distance then degree, so
    // trimming eats the far, weakly-connected fringe first.
    List<GraphNode> ranked = entities.stream()
      .map(e -> node(e, degrees.getOrDefault(e.id(), 0), hopByEntity.getOrDefault(e.id(), NO_HOP)))
      .sorted(Comparator.comparingInt((GraphNode n) -> n.id().equals(entityId) ? 0 : 1)
        .thenComparingInt(GraphNode::hop)
        .thenComparing(Comparator.comparingInt(GraphNode::degree).reversed())
        .thenComparing(GraphNode::name))
      .toList();

    int totalNeighbors = Math.max(0, ranked.size() - 1);
    boolean truncated = ranked.size() > cap;
    List<GraphNode> nodes = truncated ? ranked.subList(0, cap) : ranked;

    return new GraphNeighborhoodResponse(
      entityId,
      nodes,
      linksAmong(profile.id(), nodes),
      truncated,
      totalNeighbors);
  }

  /** Inspector detail for one entity: its relations and the memories they came from. */
  public GraphEntityResponse entity(String profileName, String entityId) {
    Profile profile = findOrThrow(profileName);

    Entity entity = store.findEntitiesByIds(profile.id(), List.of(entityId)).stream()
      .findFirst()
      .orElseThrow(() -> NotFoundException.entity(entityId));
    int degree = store.entityDegrees(profile.id(), List.of(entityId)).getOrDefault(entityId, 0);

    List<GraphEntityResponse.Relation> relations =
      store.incidentEdges(profile.id(), entityId, RELATION_LIMIT).stream()
        .map(GraphExplorerService::relation)
        .toList();

    List<MemoryResponse> memories =
      store.findMemoriesByEntities(profile.id(), List.of(entityId), MEMORY_LIMIT).stream()
        .map(memoryResponseConverter::convert)
        .toList();

    return new GraphEntityResponse(node(entity, degree, 0), relations, memories);
  }

  /**
   * Active edges with both endpoints inside {@code nodes}. Edges leaving the slice are dropped
   * rather than drawn as stubs — the node's {@code degree} is what tells the viewer more exists.
   */
  private List<GraphLink> linksAmong(String profileId, List<GraphNode> nodes) {
    if (nodes.isEmpty()) {
      return List.of();
    }
    List<String> ids = nodes.stream().map(GraphNode::id).toList();
    return store.inducedEdges(profileId, ids).stream()
      .map(GraphExplorerService::link)
      .toList();
  }

  private static GraphNode node(Entity entity, int degree, int hop) {
    return new GraphNode(entity.id(), entity.type(), entity.name(), degree, hop);
  }

  private static GraphLink link(Edge edge) {
    return new GraphLink(edge.sourceEntityId(), edge.targetEntityId(), edge.relation(), edge.memoryId());
  }

  private static GraphEntityResponse.Relation relation(IncidentEdge incident) {
    Entity other = incident.other();
    return new GraphEntityResponse.Relation(
      incident.outgoing() ? "out" : "in",
      incident.edge().relation(),
      other.id(),
      other.name(),
      other.type(),
      incident.edge().memoryId());
  }

  private static List<String> safe(List<String> types) {
    return types == null ? List.of() : types;
  }

  private static int nodeLimit(Integer requested) {
    return bounded(requested, DEFAULT_NODE_LIMIT, MAX_NODE_LIMIT);
  }

  private static int bounded(Integer requested, int fallback, int ceiling) {
    if (requested == null || requested <= 0) {
      return Math.min(fallback, ceiling);
    }
    return Math.min(requested, ceiling);
  }

  private Profile findOrThrow(String name) {
    return store.findProfile(name).orElseThrow(() -> NotFoundException.profile(name));
  }
}
