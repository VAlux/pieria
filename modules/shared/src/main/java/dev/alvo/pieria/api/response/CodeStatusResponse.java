package dev.alvo.pieria.api.response;

/**
 * Result of GET /v1/profiles/{name}/code/status: code-index size for the profile. Minimal by design;
 * richer freshness (last indexed tree hash, stale counts, watch mode) is a later feature.
 */
public record CodeStatusResponse(
  boolean present,
  long files,
  long symbols,
  long resolvedEdges,
  long heuristicEdges,
  long edges) {
}
