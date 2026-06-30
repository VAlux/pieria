package dev.alvo.pieria.api.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /v1/profiles/{name}/recall. {@code debug} opts into per-channel
 * diagnostics + candidate provenance in the response; default responses stay concise.
 * {@code fast} selects the low-latency injection path: deterministic query analysis (no model call)
 * and no synthesis, returning the retrieved memories with a {@code null} answer in ~1-3s. Used by the
 * auto-recall hooks; {@code null}/{@code false} keeps the full synthesized behavior.
 */
public record RecallRequest(
  @NotBlank String query,
  Integer limit,
  Boolean debug,
  Boolean fast) {
}
