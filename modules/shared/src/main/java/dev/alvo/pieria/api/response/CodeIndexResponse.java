package dev.alvo.pieria.api.response;

/**
 * Result of POST /v1/profiles/{name}/code: per-run counts mirroring the daemon's indexing summary.
 * The {@code summaries*} counts cover the optional LLM narrative pass (async endpoint only) and
 * are all zero when it did not run.
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
  int graphEdges,
  int summariesStored,
  int summariesSkipped,
  int summariesFailed) {
}
