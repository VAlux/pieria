package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.ClusterLinksResponse;
import dev.alvo.pieria.cluster.ClusterLinkService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/** Overlap between clusters for the entity graph's cluster layer. */
@RestController
public class ClusterLinkController {

  private final ClusterLinkService links;

  public ClusterLinkController(ClusterLinkService links) {
    this.links = links;
  }

  @GetMapping("/v1/profiles/{name}/clusters/links")
  public ClusterLinksResponse links(@PathVariable String name,
                                    @RequestParam("ids") String ids,
                                    @RequestParam(name = "minShared", defaultValue = "2") int minShared,
                                    @RequestParam(name = "limit", defaultValue = "200") int limit) {
    var clusterIds = Arrays.stream(ids.split(",")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    return new ClusterLinksResponse(links.links(name, clusterIds, minShared, limit).stream()
      .map(l -> new ClusterLinksResponse.Link(l.a(), l.b(), l.shared(), l.examples()))
      .toList());
  }
}
