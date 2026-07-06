package dev.alvo.pieria.api.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /v1/profiles/{name}/recall. {@code debug} opts into per-channel
 * diagnostics + candidate provenance in the response; default responses stay concise.
 *
 * <p>{@code mode} selects the inference tier (see {@link RecallMode}): {@code EVIDENCE} for the
 * low-latency injection path (deterministic analysis, no synthesis, {@code null} answer in ~1-3s),
 * {@code ANALYZED} for model-driven retrieval without synthesis, or {@code SYNTHESIZED} for the full
 * answer. {@code null} defers to the profile's configured default tier.
 */
public record RecallRequest(
  @NotBlank String query,
  Integer limit,
  Boolean debug,
  RecallMode mode) {
}
