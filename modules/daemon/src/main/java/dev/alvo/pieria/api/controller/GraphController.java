package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.GraphEntityResponse;
import dev.alvo.pieria.api.response.GraphNeighborhoodResponse;
import dev.alvo.pieria.api.response.GraphOverviewResponse;
import dev.alvo.pieria.api.response.GraphSearchResponse;
import dev.alvo.pieria.graph.GraphExplorerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * REST surface for the console's graph explorer, scoped to
 * {@code /v1/profiles/{name}/graph}.
 *
 * <p>There is deliberately no "give me the whole graph" endpoint. Real profiles reach tens of
 * thousands of entities and edges; every route here returns a bounded slice and reports the totals
 * alongside it, so the client can show how much it is not showing. All bounding and ranking belongs
 * to {@link GraphExplorerService} — these methods only parse params and hand back DTOs.
 */
@RestController
@RequestMapping("/v1/profiles/{name}/graph")
public class GraphController {

  private final GraphExplorerService graphExplorerService;

  public GraphController(GraphExplorerService graphExplorerService) {
    this.graphExplorerService = graphExplorerService;
  }

  /**
   * Landing view: profile totals, entity-type facets, and the top-degree entities with their edges.
   */
  @GetMapping("/overview")
  public GraphOverviewResponse overview(@PathVariable String name,
                                        @RequestParam(name = "types", required = false) List<String> types,
                                        @RequestParam(name = "limit", required = false) Integer limit) {
    return graphExplorerService.overview(name, types, limit);
  }

  /**
   * Entity name search, most-connected first, for picking a focus.
   */
  @GetMapping("/search")
  public GraphSearchResponse search(@PathVariable String name,
                                    @RequestParam(name = "q") String query,
                                    @RequestParam(name = "types", required = false) List<String> types,
                                    @RequestParam(name = "limit", required = false) Integer limit) {
    return graphExplorerService.search(name, query, types, limit);
  }

  /**
   * Focused view: a bounded walk out from one entity.
   */
  @GetMapping("/neighborhood")
  public GraphNeighborhoodResponse neighborhood(@PathVariable String name,
                                                @RequestParam(name = "entity") String entityId,
                                                @RequestParam(name = "depth", required = false) Integer depth,
                                                @RequestParam(name = "types", required = false) List<String> types,
                                                @RequestParam(name = "limit", required = false) Integer limit) {
    return graphExplorerService.neighborhood(name, entityId, depth, types, limit);
  }

  /**
   * Inspector detail for one entity: its relations and the memories they were extracted from.
   */
  @GetMapping("/entities/{id}")
  public GraphEntityResponse entity(@PathVariable String name, @PathVariable String id) {
    return graphExplorerService.entity(name, id);
  }

  /**
   * Convenience entry point for humans: redirect to the console's graph tab with this profile
   * pre-selected, so {@code /v1/profiles/{name}/graph/view} opens a ready-to-use page.
   */
  @GetMapping("/view")
  public ResponseEntity<Void> view(@PathVariable String name) {
    var viewer = URI.create("/index.html?view=graph&profile=%s"
      .formatted(URLEncoder.encode(name, StandardCharsets.UTF_8)));
    return ResponseEntity.status(HttpStatus.FOUND).location(viewer).build();
  }
}
