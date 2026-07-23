package dev.alvo.pieria.api;

import dev.alvo.pieria.api.controller.GraphController;
import dev.alvo.pieria.api.conversion.MemoryResponseConverter;
import dev.alvo.pieria.api.error.GlobalExceptionHandler;
import dev.alvo.pieria.domain.ContentId;
import dev.alvo.pieria.domain.graph.Edge;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.graph.GraphExplorerService;
import dev.alvo.pieria.storage.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test for the graph explorer surface: routing, status codes, param parsing, and response
 * serialization against a stubbed store. Selection and capping behaviour is covered by
 * {@code GraphExplorerServiceTests}, and the SQL by {@code SqliteMemoryStoreGraphTests}.
 */
@WebMvcTest(controllers = {GraphController.class, GlobalExceptionHandler.class})
@Import({GraphApiTests.Wiring.class, GraphExplorerService.class, MemoryResponseConverter.class})
class GraphApiTests {

  @Autowired
  MockMvc mvc;
  @Autowired
  MemoryStore store;

  private String alphaId;
  private String betaId;

  /**
   * A profile holding one active edge: {@code alpha --uses--> beta}, both {@code concept}, plus a
   * {@code person} entity so the type facet has something to distinguish.
   */
  @BeforeEach
  void seed() {
    Profile p = store.getOrCreateProfile("graphtest");
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "alpha uses beta", "s1", null, null));
    String memId = ContentId.forMemory(p.id(), "s1", MemoryType.FACT, "alpha uses beta");

    Entity alpha = store.upsertEntity(p.id(), Entity.of("concept", "alpha", "{}"));
    Entity beta = store.upsertEntity(p.id(), Entity.of("concept", "beta", "{}"));
    store.upsertEntity(p.id(), Entity.of("person", "ada", "{}"));
    store.upsertEdge(p.id(), new Edge(null, p.id(), alpha.id(), beta.id(), "uses", memId, null));

    alphaId = alpha.id();
    betaId = beta.id();
  }

  @Test
  void overviewReturnsTotalsFacetsNodesAndLinks() throws Exception {
    mvc.perform(get("/v1/profiles/graphtest/graph/overview"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.entityCount", is(2)))
      .andExpect(jsonPath("$.edgeCount", is(1)))
      .andExpect(jsonPath("$.truncated", is(false)))
      .andExpect(jsonPath("$.nodes", hasSize(2)))
      .andExpect(jsonPath("$.links", hasSize(1)))
      .andExpect(jsonPath("$.links[0].relation", is("uses")))
      .andExpect(jsonPath("$.links[0].source", is(alphaId)))
      .andExpect(jsonPath("$.links[0].target", is(betaId)))
      .andExpect(jsonPath("$.types", hasSize(2)));
  }

  @Test
  void overviewTypeFilterIsParsedAsARepeatableParam() throws Exception {
    mvc.perform(get("/v1/profiles/graphtest/graph/overview").param("types", "person"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.nodes", hasSize(0)))
      .andExpect(jsonPath("$.links", hasSize(0)))
      // Totals stay profile-wide: the filter narrows what is drawn, not what exists.
      .andExpect(jsonPath("$.entityCount", is(2)));
  }

  @Test
  void searchReturnsMatchesRankedByDegree() throws Exception {
    mvc.perform(get("/v1/profiles/graphtest/graph/search").param("q", "alph"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.matches", hasSize(1)))
      .andExpect(jsonPath("$.matches[0].id", is(alphaId)))
      .andExpect(jsonPath("$.matches[0].name", is("alpha")))
      .andExpect(jsonPath("$.matches[0].degree", is(1)));
  }

  @Test
  void searchWithoutQueryIsBadRequest() throws Exception {
    mvc.perform(get("/v1/profiles/graphtest/graph/search"))
      .andExpect(status().isBadRequest());
  }

  @Test
  void neighborhoodReturnsTheFocusAtHopZeroAndItsNeighbours() throws Exception {
    mvc.perform(get("/v1/profiles/graphtest/graph/neighborhood").param("entity", alphaId))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.focusId", is(alphaId)))
      .andExpect(jsonPath("$.nodes", hasSize(2)))
      .andExpect(jsonPath("$.nodes[0].id", is(alphaId)))
      .andExpect(jsonPath("$.nodes[0].hop", is(0)))
      .andExpect(jsonPath("$.nodes[1].hop", is(1)))
      .andExpect(jsonPath("$.links", hasSize(1)))
      .andExpect(jsonPath("$.truncated", is(false)))
      .andExpect(jsonPath("$.totalNeighbors", is(1)));
  }

  @Test
  void neighborhoodOfAnUnknownEntityIsNotFound() throws Exception {
    mvc.perform(get("/v1/profiles/graphtest/graph/neighborhood").param("entity", "no-such-entity"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.error", is("not_found")));
  }

  @Test
  void entityReturnsRelationsAndProvenanceMemories() throws Exception {
    mvc.perform(get("/v1/profiles/graphtest/graph/entities/" + alphaId))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.entity.id", is(alphaId)))
      .andExpect(jsonPath("$.entity.name", is("alpha")))
      .andExpect(jsonPath("$.entity.degree", is(1)))
      .andExpect(jsonPath("$.relations", hasSize(1)))
      .andExpect(jsonPath("$.relations[0].direction", is("out")))
      .andExpect(jsonPath("$.relations[0].relation", is("uses")))
      .andExpect(jsonPath("$.relations[0].otherId", is(betaId)))
      .andExpect(jsonPath("$.relations[0].otherName", is("beta")))
      .andExpect(jsonPath("$.memories", hasSize(1)))
      .andExpect(jsonPath("$.memories[0].content", is("alpha uses beta")));
  }

  @Test
  void entityUnknownIdIsNotFound() throws Exception {
    mvc.perform(get("/v1/profiles/graphtest/graph/entities/no-such-entity"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.error", is("not_found")));
  }

  @Test
  void everyRouteIsNotFoundForAMissingProfile() throws Exception {
    mvc.perform(get("/v1/profiles/ghost/graph/overview"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.error", is("not_found")));
    mvc.perform(get("/v1/profiles/ghost/graph/search").param("q", "x"))
      .andExpect(status().isNotFound());
    mvc.perform(get("/v1/profiles/ghost/graph/neighborhood").param("entity", "x"))
      .andExpect(status().isNotFound());
    mvc.perform(get("/v1/profiles/ghost/graph/entities/x"))
      .andExpect(status().isNotFound());
  }

  @Test
  void graphViewRedirectsToStaticViewerWithProfile() throws Exception {
    mvc.perform(get("/v1/profiles/graphtest/graph/view"))
      .andExpect(status().isFound())
      .andExpect(redirectedUrl("/index.html?view=graph&profile=graphtest"));
  }

  @TestConfiguration
  static class Wiring {
    private final StubMemoryStore store = new StubMemoryStore();

    @Bean("graphApiMemoryStore")
    MemoryStore memoryStore() {
      return store;
    }
  }
}
