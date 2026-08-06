package dev.alvo.pieria.config;

import dev.alvo.pieria.config.model.DaemonOverrides;
import dev.alvo.pieria.config.model.DaemonOverrides.Ingestion;
import dev.alvo.pieria.config.model.DaemonOverrides.Retrieval;
import dev.alvo.pieria.config.toml.ConfigCodec;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.stereotype.Component;

/**
 * Per-profile config overrides, pushed by the CLI from a project's merged
 * {@code .pieria/config.toml}. Owns the put/get/delete flow against {@link MemoryStore} and
 * {@link EffectiveConfigResolver}, and the {@link ResolvedConfig} → fully-populated
 * {@link DaemonOverrides} view mapping. Request-shape validation (whitelisting) stays at the
 * transport edge in {@code ProfileConfigController}, since it operates on the raw JSON body.
 */
@Component
public class ProfileConfigService {

  private final MemoryStore store;
  private final EffectiveConfigResolver configResolver;

  public ProfileConfigService(MemoryStore store, EffectiveConfigResolver configResolver) {
    this.store = store;
    this.configResolver = configResolver;
  }

  /**
   * Replace the profile's overrides wholesale (creating the profile if needed) and return the
   * resulting effective config. An empty {@code overrides} clears the stored overrides.
   */
  public DaemonOverrides put(String profileName, DaemonOverrides overrides) {
    Profile profile = store.getOrCreateProfile(profileName);
    if (overrides.isEmpty()) {
      store.clearProfileConfig(profile.id());
    } else {
      store.putProfileConfig(profile.id(), ConfigCodec.toJson(overrides));
    }
    configResolver.invalidate(profile.id());

    return effectiveFor(profile.id());
  }

  /**
   * The effective config for the profile. An unknown profile resolves to the global config (no
   * profile row is created by reading).
   */
  public DaemonOverrides effective(String profileName) {
    return store.findProfile(profileName)
      .map(profile -> effectiveFor(profile.id()))
      .orElseGet(() -> toFullOverrides(configResolver.global()));
  }

  /**
   * Remove the profile's overrides; reading falls back to the global config. Idempotent.
   */
  public void delete(String profileName) {
    store.findProfile(profileName).ifPresent(profile -> {
      store.clearProfileConfig(profile.id());
      configResolver.invalidate(profile.id());
    });
  }

  private DaemonOverrides effectiveFor(String profileId) {
    return toFullOverrides(configResolver.resolve(profileId));
  }

  /**
   * Render a ResolvedConfig as a fully-populated DaemonOverrides view (every field set).
   */
  private static DaemonOverrides toFullOverrides(ResolvedConfig resolved) {
    PieriaProperties.Ingestion ingestion = resolved.ingestion();
    PieriaProperties.Retrieval retrieval = resolved.retrieval();

    return new DaemonOverrides(
      new Ingestion(
        ingestion.chunkSizeChars(),
        ingestion.chunkOverlapMessages(),
        ingestion.maxExtractionConcurrency(),
        ingestion.interrogativeQueriesPerMemory(),
        ingestion.maxExtractedCandidatesPerChunk(),
        ingestion.graphFromExtraction()),

      new Retrieval(
        retrieval.vectorEnabled(),
        retrieval.rrfK(),
        retrieval.weightExactKey(),
        retrieval.weightFtsMemory(),
        retrieval.weightHydeVector(),
        retrieval.weightDirectVector(),
        retrieval.weightFtsMessage(),
        retrieval.weightGraph(),
        retrieval.graphDepth(),
        retrieval.graphFanout(),
        retrieval.graphSeedLimit(),
        retrieval.channelLimit(),
        retrieval.channelTimeoutMs(),
        retrieval.weightSymbolFts(),
        retrieval.weightCodeGraph(),
        retrieval.codeGraphDepth(),
        retrieval.codeGraphFanout(),
        retrieval.codeGraphSeedLimit(),
        retrieval.codeGraphMinConfidence(),
        retrieval.recallMode(),
        retrieval.nearDuplicateThreshold(),
        retrieval.semanticDuplicateThreshold()));
  }
}
