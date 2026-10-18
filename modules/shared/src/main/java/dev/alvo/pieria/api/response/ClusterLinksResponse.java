package dev.alvo.pieria.api.response;

import java.util.List;

/** GET /v1/profiles/{name}/clusters/links: cluster pairs by the entities their memories share. */
public record ClusterLinksResponse(List<Link> links) {

  /** {@code topEntityNames} lists up to three of the shared entities. */
  public record Link(String a, String b, int sharedEntities, List<String> topEntityNames) {
  }
}
