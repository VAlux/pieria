package dev.alvo.pieria.api.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /v1/profiles/{name}/recall (SPEC 9.1). {@code debug} opts into per-channel
 * diagnostics + candidate provenance in the response (phase-3 step 10); default responses stay concise.
 */
public record RecallRequest(
  @NotBlank String query,
  Integer limit,
  Boolean debug) {
}
