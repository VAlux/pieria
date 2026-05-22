package dev.alvo.pieria.api.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /v1/profiles/{name}/recall (SPEC 9.1).
 */
public record RecallRequest(
  @NotBlank String query,
  Integer limit) {
}
