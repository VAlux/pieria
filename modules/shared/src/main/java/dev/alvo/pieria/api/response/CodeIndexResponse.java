package dev.alvo.pieria.api.response;

/**
 * Result of POST /v1/profiles/{name}/code: per-run counts mirroring the daemon's indexing summary.
 */
public record CodeIndexResponse(
  int filesReceived,
  int filesSkippedUnchanged,
  int filesParsed,
  int filesFailed,
  int symbols,
  int resolvedEdges,
  int heuristicEdges,
  int memoriesStored,
  int memoriesSuperseded,
  int graphEntities,
  int graphEdges) {
}
