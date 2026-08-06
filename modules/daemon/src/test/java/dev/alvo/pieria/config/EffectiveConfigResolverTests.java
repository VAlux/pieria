package dev.alvo.pieria.config;

import dev.alvo.pieria.config.VerifyMode;

import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.storage.MemoryStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveConfigResolverTests {

  /** Minimal store fake: only the profile-config reads matter here. */
  private static final class ConfigOnlyStore implements MemoryStore {
    final Map<String, String> configs = new HashMap<>();
    int reads;

    @Override
    public Optional<String> getProfileConfig(String profileId) {
      reads++;
      return Optional.ofNullable(configs.get(profileId));
    }

    @Override
    public Profile getOrCreateProfile(String name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Profile> findProfile(String name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void insertMessages(String profileId, String sessionId, List<Message> messages) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Memory insertMemory(String profileId, Memory memory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Memory> listMemories(String profileId, MemoryType typeFilter, String sessionFilter, boolean includeSuperseded) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean forgetMemory(String profileId, String memoryId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<ExportRow> exportProfile(String profileId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<RecallCandidate> findRecallCandidates(String profileId, String query, int limit) {
      throw new UnsupportedOperationException();
    }
  }

  private static PieriaProperties globalProps() {
    return new PieriaProperties(null, null, null, null,
      new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS,
        1, 0, 0, false, 3, 3, 32, 5, true, 5000, true, 0.70),
      new PieriaProperties.Retrieval(true, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 1.0, 1.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.60, 0.78),
      null);
  }

  @Test
  void noStoredConfigResolvesToGlobal() {
    EffectiveConfigResolver resolver = new EffectiveConfigResolver(globalProps(), new ConfigOnlyStore());
    ResolvedConfig resolved = resolver.resolve("p1");
    assertThat(resolved.retrieval()).isEqualTo(globalProps().retrieval());
    assertThat(resolved.ingestion()).isEqualTo(globalProps().ingestion());
  }

  @Test
  void storedOverridesOverlayOntoGlobalAndProcessGlobalFieldsSurvive() {
    ConfigOnlyStore store = new ConfigOnlyStore();
    store.configs.put("p1",
      "{\"retrieval\":{\"weight-graph\":0.0,\"rrf-k\":30},\"ingestion\":{\"chunk-size-chars\":8000}}");
    EffectiveConfigResolver resolver = new EffectiveConfigResolver(globalProps(), store);

    ResolvedConfig resolved = resolver.resolve("p1");

    assertThat(resolved.retrieval().weightGraph()).isZero();      // overridden
    assertThat(resolved.retrieval().rrfK()).isEqualTo(30);        // overridden
    assertThat(resolved.retrieval().weightExactKey()).isEqualTo(3.0); // inherited
    assertThat(resolved.ingestion().chunkSizeChars()).isEqualTo(8000); // overridden
    assertThat(resolved.ingestion().maxExtractionConcurrency()).isEqualTo(4); // inherited
    // Process-global ingestion fields always carry global values.
    assertThat(resolved.ingestion().outboxBatchSize()).isEqualTo(32);
    assertThat(resolved.ingestion().vectorizationSchedulerEnabled()).isTrue();
  }

  @Test
  void resolutionsAreCachedUntilInvalidated() {
    ConfigOnlyStore store = new ConfigOnlyStore();
    store.configs.put("p1", "{\"retrieval\":{\"rrf-k\":30}}");
    EffectiveConfigResolver resolver = new EffectiveConfigResolver(globalProps(), store);

    assertThat(resolver.resolve("p1").retrieval().rrfK()).isEqualTo(30);
    resolver.resolve("p1");
    assertThat(store.reads).isEqualTo(1); // second resolve served from cache

    store.configs.put("p1", "{\"retrieval\":{\"rrf-k\":90}}");
    assertThat(resolver.resolve("p1").retrieval().rrfK()).isEqualTo(30); // still cached

    resolver.invalidate("p1");
    assertThat(resolver.resolve("p1").retrieval().rrfK()).isEqualTo(90); // re-read after invalidation
  }

  @Test
  void unparseableStoredConfigFailsOpenToGlobal() {
    ConfigOnlyStore store = new ConfigOnlyStore();
    store.configs.put("p1", "not json at all {{{");
    EffectiveConfigResolver resolver = new EffectiveConfigResolver(globalProps(), store);

    assertThat(resolver.resolve("p1").retrieval()).isEqualTo(globalProps().retrieval());
  }

  @Test
  void profilesAreIsolated() {
    ConfigOnlyStore store = new ConfigOnlyStore();
    store.configs.put("p1", "{\"retrieval\":{\"rrf-k\":30}}");
    EffectiveConfigResolver resolver = new EffectiveConfigResolver(globalProps(), store);

    assertThat(resolver.resolve("p1").retrieval().rrfK()).isEqualTo(30);
    assertThat(resolver.resolve("p2").retrieval().rrfK()).isEqualTo(60);
  }
}
