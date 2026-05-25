package dev.alvo.pieria.api.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /v1/profiles/{name}/recall. {@code debug} opts into per-channel
 * diagnostics + candidate provenance in the response; default responses stay concise.
 */
public record RecallRequest(
  @NotBlank String query,
  Integer limit,
  Boolean debug) {
}
