package dev.alvo.pieria.api.request;

import dev.alvo.pieria.api.response.MemoryOrigin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Body of POST /v1/profiles/{name}/sources.
 *
 * @param mode            {@code full} or {@code raw}; null means full
 * @param intervalSeconds null means six hours
 * @param start           whether to activate at once rather than wait to be resumed
 */
public record CreateSourceRequest(
  @NotBlank @Pattern(regexp = MemoryOrigin.SOURCE_ID_REGEX) String id,
  @NotBlank String harvester,
  ObjectNode config,
  Map<String, String> secrets,
  String mode,
  Long intervalSeconds,
  Integer maxItemsPerRun,
  Long maxCharsPerRun,
  Boolean start) {

  @Override
  public String toString() {
    return "CreateSourceRequest[id=" + id + ", harvester=" + harvester + "]";
  }
}
