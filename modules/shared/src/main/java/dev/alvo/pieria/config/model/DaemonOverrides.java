package dev.alvo.pieria.config.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.alvo.pieria.api.request.RecallMode;

/**
 * The daemon-overridable subset of {@code pieria.*} — the {@code [pieria]} tree of a Pieria config
 * file. This single type is the PUT payload of {@code /v1/profiles/{name}/config} <em>and</em> the
 * shape the daemon persists per profile, so the CLI and daemon cannot drift.
 *
 * <p>Only request-time tuning is included. Process-global properties (provider connection,
 * embedding model/dimension, db path, daemon host/port, vectorization worker) are deliberately
 * absent: the daemon whitelists against this type and rejects anything else.
 *
 * <p>Every field is a nullable wrapper; {@code null} means "inherit the global value". Serialized
 * with kebab-case keys (see {@code ConfigCodec}), matching the TOML authoring format and the
 * Spring relaxed-binding names in {@code pieria.properties}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DaemonOverrides(Ingestion ingestion, Retrieval retrieval) {

  /** Mirrors the per-profile subset of {@code PieriaProperties.Ingestion}. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Ingestion(
    Integer chunkSizeChars,
    Integer chunkOverlapMessages,
    Integer maxExtractionConcurrency,
    Integer interrogativeQueriesPerMemory,
    Integer maxExtractedCandidatesPerChunk,
    Boolean graphFromExtraction) {

  }

  /** Mirrors {@code PieriaProperties.Retrieval} — all of it is per-profile tunable. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Retrieval(
    Boolean vectorEnabled,
    Integer rrfK,
    Double weightExactKey,
    Double weightFtsMemory,
    Double weightHydeVector,
    Double weightDirectVector,
    Double weightFtsMessage,
    Double weightGraph,
    Integer graphDepth,
    Integer graphFanout,
    Integer graphSeedLimit,
    Integer channelLimit,
    Long channelTimeoutMs,
    Double weightSymbolFts,
    Double weightCodeGraph,
    Integer codeGraphDepth,
    Integer codeGraphFanout,
    Integer codeGraphSeedLimit,
    String codeGraphMinConfidence,
    RecallMode recallMode,
    Double nearDuplicateThreshold,
    Double semanticDuplicateThreshold) {
  }

  /**
   * True when no override is set at all (PUTting this is equivalent to DELETE).
   *
   * <p>This component list is hand-maintained and has already drifted once (two {@code Retrieval}
   * fields were missing, which silently cleared a profile's overrides on save instead of reporting
   * them). {@code ConfigRecordDriftTests} fails on any future record component that isn't threaded
   * through here, so a drift is caught at test time rather than shipped.
   */
  @JsonIgnore
  public boolean isEmpty() {
    return (ingestion == null || allNull(ingestion.chunkSizeChars(), ingestion.chunkOverlapMessages(),
      ingestion.maxExtractionConcurrency(), ingestion.interrogativeQueriesPerMemory(),
      ingestion.maxExtractedCandidatesPerChunk(), ingestion.graphFromExtraction()))
      && (retrieval == null || allNull(retrieval.vectorEnabled(), retrieval.rrfK(), retrieval.weightExactKey(),
      retrieval.weightFtsMemory(), retrieval.weightHydeVector(), retrieval.weightDirectVector(),
      retrieval.weightFtsMessage(), retrieval.weightGraph(), retrieval.graphDepth(), retrieval.graphFanout(),
      retrieval.graphSeedLimit(), retrieval.channelLimit(), retrieval.channelTimeoutMs(),
      retrieval.weightSymbolFts(), retrieval.weightCodeGraph(), retrieval.codeGraphDepth(),
      retrieval.codeGraphFanout(), retrieval.codeGraphSeedLimit(), retrieval.codeGraphMinConfidence(),
      retrieval.recallMode(), retrieval.nearDuplicateThreshold(), retrieval.semanticDuplicateThreshold()));
  }

  private static boolean allNull(Object... values) {
    for (Object value : values) {
      if (value != null) {
        return false;
      }
    }
    return true;
  }
}
