package dev.alvo.pieria.api.request;

import dev.alvo.pieria.api.response.MemoryOrigin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * Body of POST /v1/profiles/{name}/recall. {@code debug} opts into per-channel
 * diagnostics + candidate provenance in the response; default responses stay concise.
 *
 * <p>{@code mode} selects the inference tier (see {@link RecallMode}): {@code EVIDENCE} for the
 * low-latency injection path (deterministic analysis, no synthesis, {@code null} answer in ~1-3s),
 * {@code ANALYZED} for model-driven retrieval without synthesis, or {@code SYNTHESIZED} for the full
 * answer. {@code null} defers to the profile's configured default tier.
 *
 * <p>{@code includeSources} limits the recall to memories harvested from those sources;
 * {@code excludeSources} hides those sources and wins over {@code includeSources}. Empty or
 * {@code null} applies no limit.
 */
public record RecallRequest(
  @NotBlank String query,
  Integer limit,
  Boolean debug,
  RecallMode mode,
  String asOf,
  String knownAsOf,
  List<@Pattern(regexp = MemoryOrigin.SOURCE_ID_REGEX) String> includeSources,
  List<@Pattern(regexp = MemoryOrigin.SOURCE_ID_REGEX) String> excludeSources) {

  public RecallRequest {
    includeSources = includeSources == null ? List.of() : List.copyOf(includeSources);
    excludeSources = excludeSources == null ? List.of() : List.copyOf(excludeSources);
  }

  public RecallRequest(String query, Integer limit, Boolean debug, RecallMode mode) {
    this(query, limit, debug, mode, null, null);
  }

  public RecallRequest(String query, Integer limit, Boolean debug, RecallMode mode, String asOf, String knownAsOf) {
    this(query, limit, debug, mode, asOf, knownAsOf, null, null);
  }
}
