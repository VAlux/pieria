package dev.alvo.pieria.config;

import dev.alvo.pieria.config.model.DaemonOverrides;
import dev.alvo.pieria.config.model.DaemonOverrides.Ingestion;
import dev.alvo.pieria.config.model.DaemonOverrides.Retrieval;
import dev.alvo.pieria.config.toml.ConfigCodec;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.storage.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(ProfileConfigService.class);

  private final MemoryStore store;
  private final EffectiveConfigResolver configResolver;

  public ProfileConfigService(MemoryStore store, EffectiveConfigResolver configResolver) {
    this.store = store;
    this.configResolver = configResolver;
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

  /**
   * All three layers for one profile in a single read. The console needs the sparse override map
   * to tell "overridden to the global value" apart from "inherited" — diffing effective against
   * global cannot distinguish them.
   */
  public ProfileConfigDetail detail(String profileName) {
    DaemonOverrides global = toFullOverrides(configResolver.global());

    return store.findProfile(profileName)
      .map(profile -> new ProfileConfigDetail(
        global,
        storedOverrides(profile.id()),
        effectiveFor(profile.id())))
      .orElseGet(() -> new ProfileConfigDetail(
        global,
        new DaemonOverrides(null, null),
        global));
  }

  /**
   * The profile's raw stored overrides, or an empty set. Fail-open like the resolver: a corrupt
   * row must not break the config page. The failure is logged rather than swallowed outright
   * because the console's save path PUTs the whole override set back — a silent read failure here
   * would render as "no overrides" and the next save would quietly erase the profile's real ones.
   */
  private DaemonOverrides storedOverrides(String profileId) {
    try {
      return store.getProfileConfig(profileId)
        .map(json -> ConfigCodec.bind(ConfigCodec.parseJson(json), DaemonOverrides.class))
        .orElseGet(() -> new DaemonOverrides(null, null));
    } catch (RuntimeException e) {
      log.warn("could not read stored config overrides for {} ({}); reporting none", profileId, e.toString());
      return new DaemonOverrides(null, null);
    }
  }

  private DaemonOverrides effectiveFor(String profileId) {
    return toFullOverrides(configResolver.resolve(profileId));
  }
}
