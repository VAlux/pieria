package dev.alvo.pieria.config;

import dev.alvo.pieria.config.model.DaemonOverrides;
import dev.alvo.pieria.config.toml.ConfigCodec;
import dev.alvo.pieria.storage.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the effective per-profile configuration: the global Spring-bound
 * {@link PieriaProperties} overlaid with the profile's persisted overrides
 * ({@code profile_config} row, pushed via {@code PUT /v1/profiles/{name}/config}).
 *
 * <p>Resolutions are cached by profile id; the config controller invalidates on every write
 * (single-writer daemon, so invalidation is exact). Resolution is fail-open: a missing row,
 * unparseable JSON, or a store that does not support profile config all yield the global config —
 * configuration must never break recall or ingest.
 */
@Component
public class EffectiveConfigResolver {

  private static final Logger log = LoggerFactory.getLogger(EffectiveConfigResolver.class);

  private final PieriaProperties properties;
  private final MemoryStore store;
  private final ConcurrentHashMap<String, ResolvedConfig> cache = new ConcurrentHashMap<>();

  @Autowired
  public EffectiveConfigResolver(PieriaProperties properties, MemoryStore store) {
    this.properties = properties;
    this.store = store;
  }

  /**
   * Global-only resolver for tests and the eval harness: never consults a store, every profile
   * resolves to the global configuration.
   */
  public static EffectiveConfigResolver withoutOverrides(PieriaProperties properties) {
    return new EffectiveConfigResolver(properties, null);
  }

  /**
   * The effective configuration for the given profile id (cached).
   */
  public ResolvedConfig resolve(String profileId) {
    return cache.computeIfAbsent(profileId, this::load);
  }

  /**
   * The global configuration with no per-profile overrides applied.
   */
  public ResolvedConfig global() {
    return new ResolvedConfig(properties.ingestion(), properties.retrieval());
  }

  /**
   * Drop the cached resolution for a profile; the next {@link #resolve} re-reads the store.
   */
  public void invalidate(String profileId) {
    cache.remove(profileId);
  }

  private ResolvedConfig load(String profileId) {
    ResolvedConfig global = new ResolvedConfig(properties.ingestion(), properties.retrieval());
    if (store == null) {
      return global;
    }
    try {
      return store.getProfileConfig(profileId)
        .map(json -> overlay(global, ConfigCodec.bind(ConfigCodec.parseJson(json), DaemonOverrides.class)))
        .orElse(global);
    } catch (RuntimeException e) {
      log.warn("could not resolve per-profile config for {} ({}); using global config", profileId, e.toString());
      return global;
    }
  }

  /**
   * Field-by-field overlay: a null override inherits the global value. The process-global
   * ingestion fields (outbox batching/retries, vectorization scheduler) are never overridden.
   */
  private static ResolvedConfig overlay(ResolvedConfig global, DaemonOverrides overrides) {
    return new ResolvedConfig(
      overlayIngestion(global.ingestion(), overrides.ingestion()),
      overlayRetrieval(global.retrieval(), overrides.retrieval()));
  }

  private static PieriaProperties.Ingestion overlayIngestion(PieriaProperties.Ingestion g,
                                                             DaemonOverrides.Ingestion o) {
    if (o == null) {
      return g;
    }
    return new PieriaProperties.Ingestion(
      nvl(o.chunkSizeChars(), g.chunkSizeChars()),
      nvl(o.chunkOverlapMessages(), g.chunkOverlapMessages()),
      nvl(o.maxExtractionConcurrency(), g.maxExtractionConcurrency()),
      nvl(o.detailPassMinMessages(), g.detailPassMinMessages()),
      g.extractionSamples(),
      g.outboxBatchSize(),
      g.outboxMaxAttempts(),
      g.vectorizationSchedulerEnabled(),
      g.vectorizationIntervalMs());
  }

  private static PieriaProperties.Retrieval overlayRetrieval(PieriaProperties.Retrieval g,
                                                             DaemonOverrides.Retrieval o) {
    if (o == null) {
      return g;
    }
    return new PieriaProperties.Retrieval(
      nvl(o.vectorEnabled(), g.vectorEnabled()),
      nvl(o.rrfK(), g.rrfK()),
      nvl(o.weightExactKey(), g.weightExactKey()),
      nvl(o.weightFtsMemory(), g.weightFtsMemory()),
      nvl(o.weightHydeVector(), g.weightHydeVector()),
      nvl(o.weightDirectVector(), g.weightDirectVector()),
      nvl(o.weightFtsMessage(), g.weightFtsMessage()),
      nvl(o.weightGraph(), g.weightGraph()),
      nvl(o.graphDepth(), g.graphDepth()),
      nvl(o.graphFanout(), g.graphFanout()),
      nvl(o.graphSeedLimit(), g.graphSeedLimit()),
      nvl(o.channelLimit(), g.channelLimit()),
      nvl(o.channelTimeoutMs(), g.channelTimeoutMs()),
      nvl(o.weightSymbolFts(), g.weightSymbolFts()),
      nvl(o.weightCodeGraph(), g.weightCodeGraph()),
      nvl(o.codeGraphDepth(), g.codeGraphDepth()),
      nvl(o.codeGraphFanout(), g.codeGraphFanout()),
      nvl(o.codeGraphSeedLimit(), g.codeGraphSeedLimit()),
      nvl(o.codeGraphMinConfidence(), g.codeGraphMinConfidence()));
  }

  private static <T> T nvl(T override, T global) {
    return override != null ? override : global;
  }
}
