package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.NotFoundException;
import dev.alvo.pieria.domain.Profile;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.model.ModelUnavailableException;
import dev.alvo.pieria.domain.QueryAnalysis;
import dev.alvo.pieria.storage.MemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the Phase-3 read pipeline orchestration (phase-3 steps 5-10): parallel
 * channels, weighted RRF fusion, deterministic-analysis fallback, graceful vector degradation,
 * temporal-fact injection, critical-vs-soft channel failure, and debug diagnostics. Uses a
 * configurable in-memory {@link MemoryStore} fake and the deterministic {@link FakeModelGateway}.
 */
class RetrievalServiceTests {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static Memory mem(String id, String content, MemoryType type, String topicKey, Instant at) {
    return new Memory(id, "s1", type, content, topicKey, null, false, "{}", null, at);
  }

  private static PieriaProperties.Retrieval retrievalCfg() {
    return new PieriaProperties.Retrieval(true, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 10, 3000);
  }

  private static PieriaProperties props() {
    return new PieriaProperties(null, null, null, null,
      new PieriaProperties.Ingestion(10000, 2, 4, 9, 32, 5, false, 5000), retrievalCfg());
  }

  private RetrievalService service(MemoryStore store, FakeModelGateway model) {
    return new RetrievalService(store, model, new DeterministicQueryAnalyzer(), props());
  }

  @Test
  void fusesAcrossChannelsWithDeterministicOrder() {
    Memory shared = mem("m1", "user prefers tea", MemoryType.FACT, "user.drink", T0);
    Memory ftsOnly = mem("m2", "tea is grown in Assam", MemoryType.FACT, null, T0.plusSeconds(10));
    FakeStore store = new FakeStore();
    store.exactKey = List.of(shared);            // weight 3.0, rank 1
    store.ftsMemory = List.of(ftsOnly, shared);  // weight 1.0, ranks 1 and 2

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "tea", 10, false);

    List<RecallCandidate> c = result.candidates();
    assertThat(c).hasSize(2);
    // shared hit by exact-key (3/61) + fts rank2 (1/62) outranks fts-only (1/61).
    assertThat(c.get(0).memory().id()).isEqualTo("m1");
    assertThat(c.get(0).source()).contains("exact_key").contains("fts_memory");
    assertThat(c.get(1).memory().id()).isEqualTo("m2");
    assertThat(result.answer()).contains("user prefers tea");
  }

  @Test
  void degradesGracefullyWhenVectorUnavailable() {
    FakeStore store = new FakeStore();
    store.vectorAvailable = false;
    store.ftsMemory = List.of(mem("m1", "fact one", MemoryType.FACT, null, T0));

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "anything", 10, false);

    assertThat(store.vectorSearchCalls).isZero();   // never queried when capability is off
    assertThat(result.candidates()).extracting(rc -> rc.memory().id()).containsExactly("m1");
  }

  @Test
  void usesVectorChannelsWhenAvailable() {
    FakeStore store = new FakeStore();
    store.vectorAvailable = true;
    store.vectorHits = List.of(mem("v1", "semantic hit", MemoryType.FACT, null, T0));

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "meaning", 10, false);

    assertThat(store.vectorSearchCalls).isPositive();
    assertThat(result.candidates()).extracting(rc -> rc.memory().id()).contains("v1");
  }

  @Test
  void fallsBackToDeterministicAnalyzerWhenModelAnalysisFails() {
    FakeStore store = new FakeStore();
    store.exactKey = List.of(mem("m1", "berlin is the capital", MemoryType.FACT, "city.capital", T0));
    // analyzeQuery throws, but embed/synthesize still work (only analysis is down).
    FakeModelGateway model = new FakeModelGateway() {
      @Override
      public QueryAnalysis analyzeQuery(String query) {
        throw new ModelUnavailableException("analysis down");
      }
    };

    RecallResult result = service(store, model).recall("p", "capital city", 10, false);

    // Deterministic fallback produced topic keys, so the exact-key channel still ran.
    assertThat(store.exactKeyCalls).isPositive();
    assertThat(result.answer()).isNotBlank();
  }

  @Test
  void temporalFactsReachSynthesis() {
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(mem("m1", "a fact", MemoryType.FACT, null, T0));

    RecallResult result = service(store, new FakeModelGateway())
      .recall("p", "what happened on 2026-01-01?", 10, false);

    assertThat(result.temporalFacts()).isNotEmpty();
    // FakeModelGateway's 3-arg synthesis echoes the temporal-fact count.
    assertThat(result.answer()).contains("temporal fact(s)");
  }

  @Test
  void criticalChannelFailurePropagates() {
    FakeStore store = new FakeStore();
    store.ftsMemoryError = new IllegalStateException("disk gone");

    assertThatThrownBy(() -> service(store, new FakeModelGateway()).recall("p", "tea", 10, false))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("disk gone");
  }

  @Test
  void vectorChannelFailureIsSoftAndRecorded() {
    FakeStore store = new FakeStore();
    store.vectorAvailable = true;
    store.vectorError = new IllegalStateException("vec index busy");
    store.ftsMemory = List.of(mem("m1", "still here", MemoryType.FACT, null, T0));

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "tea", 10, true);

    // Recall still succeeds on FTS; the vector channels are marked failed in diagnostics.
    assertThat(result.candidates()).extracting(rc -> rc.memory().id()).containsExactly("m1");
    assertThat(result.diagnostics()).isNotNull();
    assertThat(result.diagnostics().channels())
      .anySatisfy(d -> assertThat(d.failed()).isTrue());
  }

  @Test
  void debugDiagnosticsCollectedWhenRequested() {
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(mem("m1", "a fact", MemoryType.FACT, null, T0));

    RecallResult debug = service(store, new FakeModelGateway()).recall("p", "tea", 10, true);
    assertThat(debug.diagnostics()).isNotNull();
    assertThat(debug.diagnostics().channels()).hasSize(5); // one per channel

    RecallResult concise = service(store, new FakeModelGateway()).recall("p", "tea", 10, false);
    assertThat(concise.diagnostics()).isNull();
  }

  @Test
  void unknownProfileIsNotFound() {
    FakeStore store = new FakeStore();
    store.profile = null;
    assertThatThrownBy(() -> service(store, new FakeModelGateway()).recall("ghost", "tea", 10, false))
      .isInstanceOf(NotFoundException.class);
  }

  /** Minimal configurable {@link MemoryStore}: only the methods the read path touches are wired. */
  private static final class FakeStore implements MemoryStore {
    Profile profile = new Profile("prof-1", "p", T0);
    boolean vectorAvailable = false;
    List<Memory> exactKey = List.of();
    List<Memory> ftsMemory = List.of();
    List<Memory> ftsMessage = List.of();
    List<Memory> vectorHits = List.of();
    RuntimeException ftsMemoryError;
    RuntimeException vectorError;
    int vectorSearchCalls;
    int exactKeyCalls;

    @Override
    public Optional<Profile> findProfile(String name) {
      return Optional.ofNullable(profile);
    }

    @Override
    public boolean isVectorSearchAvailable() {
      return vectorAvailable;
    }

    @Override
    public List<Memory> searchMemoriesFts(String profileId, String matchQuery, int limit) {
      if (ftsMemoryError != null) {
        throw ftsMemoryError;
      }
      return ftsMemory;
    }

    @Override
    public List<Memory> searchMemoriesByMessageFts(String profileId, String matchQuery, int limit) {
      return ftsMessage;
    }

    @Override
    public List<Memory> exactKeyLookup(String profileId, List<String> topicKeys, int limit) {
      exactKeyCalls++;
      return exactKey;
    }

    @Override
    public List<Memory> vectorSearch(String profileId, float[] queryEmbedding, int limit) {
      vectorSearchCalls++;
      if (vectorError != null) {
        throw vectorError;
      }
      return vectorHits;
    }

    // --- unused write/admin surface for the read-path tests ---
    @Override public Profile getOrCreateProfile(String name) { throw new UnsupportedOperationException(); }
    @Override public void insertMessages(String p, String s, List<dev.alvo.pieria.domain.Message> m) { }
    @Override public Memory insertMemory(String p, Memory m) { throw new UnsupportedOperationException(); }
    @Override public List<Memory> listMemories(String p, MemoryType t, String s) { return List.of(); }
    @Override public boolean forgetMemory(String p, String id) { return false; }
    @Override public List<dev.alvo.pieria.domain.ExportRow> exportProfile(String p) { return List.of(); }
    @Override public List<RecallCandidate> findRecallCandidates(String p, String q, int l) { return List.of(); }
  }
}
