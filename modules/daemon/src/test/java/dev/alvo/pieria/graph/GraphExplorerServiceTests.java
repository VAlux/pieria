package dev.alvo.pieria.graph;

import dev.alvo.pieria.api.conversion.MemoryResponseConverter;
import dev.alvo.pieria.api.response.GraphNode;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.domain.graph.Edge;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.graph.GraphCounts;
import dev.alvo.pieria.domain.graph.IncidentEdge;
import dev.alvo.pieria.domain.graph.NeighborHop;
import dev.alvo.pieria.domain.graph.RankedEntity;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.storage.MemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the explorer's bounding rules — the part that decides which slice of a
 * too-large graph the viewer actually gets. No web context, no database.
 */
class GraphExplorerServiceTests {

  private static final String PROFILE = "p1";
  private static final String PROFILE_ID = "pid-1";

  private final FakeStore store = new FakeStore();
  private final GraphExplorerService service =
    new GraphExplorerService(store, new MemoryResponseConverter());

  // ---- overview --------------------------------------------------------------------------------

  @Test
  void overviewCapsNodesAndReportsTruncationWhileKeepingTotalsProfileWide() {
    store.entities = hubs(50);
    store.counts = new GraphCounts(50, 120);

    var overview = service.overview(PROFILE, List.of(), 10);

    assertEquals(10, overview.nodes().size(), "capped at the requested limit");
    assertTrue(overview.truncated());
    assertEquals(50, overview.entityCount(), "totals describe the profile, not the returned slice");
    assertEquals(120, overview.edgeCount());
  }

  @Test
  void overviewIsNotTruncatedWhenEverythingFits() {
    store.entities = hubs(4);
    store.counts = new GraphCounts(4, 3);

    var overview = service.overview(PROFILE, List.of(), 10);

    assertEquals(4, overview.nodes().size());
    assertFalse(overview.truncated());
  }

  @Test
  void overviewIsNotTruncatedWhenTheSliceIsExactlyFull() {
    store.entities = hubs(10);
    store.counts = new GraphCounts(10, 9);

    var overview = service.overview(PROFILE, List.of(), 10);

    assertEquals(10, overview.nodes().size());
    assertFalse(overview.truncated(), "exactly-full is not truncated");
  }

  @Test
  void overviewClampsAnAbsurdLimitToTheHardCeiling() {
    store.entities = hubs(GraphExplorerService.MAX_NODE_LIMIT + 500);
    store.counts = new GraphCounts(store.entities.size(), 0);

    var overview = service.overview(PROFILE, List.of(), 100_000);

    assertEquals(GraphExplorerService.MAX_NODE_LIMIT, overview.nodes().size());
    assertTrue(overview.truncated());
  }

  @Test
  void overviewFallsBackToTheDefaultLimitWhenNoneIsGiven() {
    store.entities = hubs(GraphExplorerService.DEFAULT_NODE_LIMIT + 50);
    store.counts = new GraphCounts(store.entities.size(), 0);

    assertEquals(GraphExplorerService.DEFAULT_NODE_LIMIT,
      service.overview(PROFILE, null, null).nodes().size());
  }

  @Test
  void overviewOnAnUnknownProfileIsNotFound() {
    assertThrows(NotFoundException.class, () -> service.overview("ghost", List.of(), 10));
  }

  // ---- neighborhood ----------------------------------------------------------------------------

  @Test
  void neighborhoodKeepsTheFocusAndTheNearestBestConnectedNeighbours() {
    // focus + 5 neighbours at hop 1 with descending degree, + 1 far neighbour at hop 2.
    store.entities = new ArrayList<>();
    store.entities.add(ranked("focus", 99));
    for (int i = 0; i < 5; i++) {
      store.entities.add(ranked("near-" + i, 50 - i));
    }
    store.entities.add(ranked("far", 100));
    store.hops = new LinkedHashMap<>();
    store.hops.put("id-focus", 0);
    for (int i = 0; i < 5; i++) {
      store.hops.put("id-near-" + i, 1);
    }
    store.hops.put("id-far", 2);

    var result = service.neighborhood(PROFILE, "id-focus", 2, List.of(), 4);

    assertEquals(4, result.nodes().size());
    assertEquals("id-focus", result.nodes().get(0).id(), "the focus always survives the cap");
    assertEquals(0, result.nodes().get(0).hop());
    assertTrue(result.truncated());
    assertEquals(6, result.totalNeighbors(), "reports what the walk actually reached");

    // Trimming eats the far fringe first, even though "far" is the highest-degree node overall.
    assertFalse(result.nodes().stream().anyMatch(n -> n.id().equals("id-far")));
    assertEquals(List.of("id-near-0", "id-near-1", "id-near-2"),
      result.nodes().stream().skip(1).map(GraphNode::id).toList());
  }

  @Test
  void neighborhoodIsNotTruncatedWhenEverythingFits() {
    store.entities = List.of(ranked("focus", 1), ranked("near", 1));
    store.hops = new LinkedHashMap<>(Map.of("id-focus", 0, "id-near", 1));

    var result = service.neighborhood(PROFILE, "id-focus", 1, List.of(), 50);

    assertFalse(result.truncated());
    assertEquals(1, result.totalNeighbors());
    assertEquals(2, result.nodes().size());
  }

  @Test
  void neighborhoodOfAnUnknownEntityIsNotFound() {
    store.entities = List.of();
    store.hops = new LinkedHashMap<>();

    assertThrows(NotFoundException.class,
      () -> service.neighborhood(PROFILE, "no-such-entity", 1, List.of(), 50));
  }

  @Test
  void neighborhoodClampsDepthToTheMaximum() {
    store.entities = List.of(ranked("focus", 0));
    store.hops = new LinkedHashMap<>(Map.of("id-focus", 0));

    service.neighborhood(PROFILE, "id-focus", 99, List.of(), 50);

    assertEquals(GraphExplorerService.MAX_DEPTH, store.lastDepth);
  }

  @Test
  void neighborhoodDefaultsToOneHopWhenNoDepthIsGiven() {
    store.entities = List.of(ranked("focus", 0));
    store.hops = new LinkedHashMap<>(Map.of("id-focus", 0));

    service.neighborhood(PROFILE, "id-focus", null, List.of(), 50);

    assertEquals(1, store.lastDepth);
  }

  // ---- search ----------------------------------------------------------------------------------

  @Test
  void searchClampsItsLimitAndMarksMatchesAsUnfocused() {
    store.entities = hubs(5);

    var matches = service.search(PROFILE, "hub", List.of(), 100_000).matches();

    assertEquals(Math.min(5, GraphExplorerService.MAX_SEARCH_LIMIT), matches.size());
    assertTrue(matches.stream().allMatch(m -> m.hop() == -1), "search results are not part of a walk");
    assertEquals(GraphExplorerService.MAX_SEARCH_LIMIT, store.lastSearchLimit);
  }

  // ---- fakes -----------------------------------------------------------------------------------

  private static List<RankedEntity> hubs(int count) {
    List<RankedEntity> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      out.add(ranked("hub-" + i, count - i));
    }
    return out;
  }

  private static RankedEntity ranked(String name, int degree) {
    return new RankedEntity(
      new Entity("id-" + name, PROFILE_ID, "concept", name, "{}", Instant.EPOCH), degree);
  }

  /** Hand-rolled store returning exactly what each test sets up; records what it was asked for. */
  private static final class FakeStore implements MemoryStore {

    List<RankedEntity> entities = List.of();
    Map<String, Integer> hops = new LinkedHashMap<>();
    GraphCounts counts = GraphCounts.empty();
    int lastDepth;
    int lastSearchLimit;

    @Override
    public Optional<Profile> findProfile(String name) {
      return PROFILE.equals(name)
        ? Optional.of(new Profile(PROFILE_ID, PROFILE, Instant.EPOCH))
        : Optional.empty();
    }

    @Override
    public GraphCounts graphCounts(String profileId) {
      return counts;
    }

    @Override
    public Map<String, Integer> entityTypeCounts(String profileId) {
      return Map.of("concept", entities.size());
    }

    @Override
    public List<RankedEntity> topEntitiesByDegree(String profileId, List<String> types, int limit) {
      return entities.stream().limit(limit).toList();
    }

    @Override
    public List<RankedEntity> searchEntities(String profileId, String query, List<String> types, int limit) {
      lastSearchLimit = limit;
      return entities.stream().limit(limit).toList();
    }

    @Override
    public List<NeighborHop> graphNeighborhood(String profileId, String seedEntityId, int depth,
                                               List<String> types, int fanout) {
      lastDepth = depth;
      return hops.entrySet().stream()
        .map(e -> new NeighborHop(e.getKey(), e.getValue()))
        .toList();
    }

    @Override
    public List<Entity> findEntitiesByIds(String profileId, List<String> entityIds) {
      return entities.stream()
        .map(RankedEntity::entity)
        .filter(e -> entityIds.contains(e.id()))
        .toList();
    }

    @Override
    public Map<String, Integer> entityDegrees(String profileId, List<String> entityIds) {
      Map<String, Integer> out = new LinkedHashMap<>();
      entities.stream()
        .filter(r -> entityIds.contains(r.entity().id()))
        .forEach(r -> out.put(r.entity().id(), r.degree()));
      return out;
    }

    @Override
    public List<Edge> inducedEdges(String profileId, List<String> entityIds) {
      return List.of();
    }

    @Override
    public List<IncidentEdge> incidentEdges(String profileId, String entityId, int limit) {
      return List.of();
    }

    @Override
    public List<Memory> findMemoriesByEntities(String profileId, List<String> entityIds, int limit) {
      return List.of();
    }

    // ---- unused write/read surface ----

    @Override
    public Profile getOrCreateProfile(String name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void insertMessages(String profileId, String sessionId, List<Message> messages) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Memory insertMemory(String profileId, Memory memory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Memory> listMemories(String profileId, MemoryType typeFilter, String sessionFilter,
                                     boolean includeSuperseded) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean forgetMemory(String profileId, String memoryId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<ExportRow> exportProfile(String profileId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<RecallCandidate> findRecallCandidates(String profileId, String query, int limit) {
      throw new UnsupportedOperationException();
    }
  }
}
