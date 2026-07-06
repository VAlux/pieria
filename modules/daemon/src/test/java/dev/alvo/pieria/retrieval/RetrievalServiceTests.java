package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.code.CodeIndexingService;
import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.code.CodeEdge;
import dev.alvo.pieria.domain.code.CodeRelation;
import dev.alvo.pieria.domain.code.CodeSymbol;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.model.ModelUnavailableException;
import dev.alvo.pieria.retrieval.model.QueryAnalysis;
import dev.alvo.pieria.storage.CodeIndexStore;
import dev.alvo.pieria.storage.CodeIndexStore.EdgeEvidence;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.storage.NoOpCodeIndexStore;
import dev.alvo.pieria.tools.Tokens;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the read pipeline orchestration: parallel
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
    return new PieriaProperties.Retrieval(true, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 0.0, 0.0, 2, 20, 8, "heuristic");
  }

  private static PieriaProperties props() {
    return new PieriaProperties(null, null, null, null,
      new PieriaProperties.Ingestion(10000, 2, 4, 9, 1, 32, 5, false, 5000), retrievalCfg(), null);
  }

  private RetrievalService service(MemoryStore store, FakeModelGateway model) {
    return new RetrievalService(store, model, new DeterministicQueryAnalyzer(), new NoOpCodeIndexStore(),
      EffectiveConfigResolver.withoutOverrides(props()));
  }

  /** As {@link #service}, but with the code-graph wave enabled over the given code-index store. */
  private RetrievalService serviceWithCodeGraph(MemoryStore store, FakeModelGateway model, CodeIndexStore codeStore) {
    PieriaProperties.Retrieval cfg = new PieriaProperties.Retrieval(
      true, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 0.0, 1.0, 2, 20, 8, "heuristic");
    PieriaProperties props = new PieriaProperties(null, null, null, null,
      new PieriaProperties.Ingestion(10000, 2, 4, 9, 1, 32, 5, false, 5000), cfg, null);
    return new RetrievalService(store, model, new DeterministicQueryAnalyzer(), codeStore,
      EffectiveConfigResolver.withoutOverrides(props));
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
  void recallRecordsEvidenceAndAnswerUsage() {
    Memory shared = mem("m1", "user prefers tea", MemoryType.FACT, "user.drink", T0);
    Memory ftsOnly = mem("m2", "tea is grown in Assam", MemoryType.FACT, null, T0.plusSeconds(10));
    FakeStore store = new FakeStore();
    store.exactKey = List.of(shared);
    store.ftsMemory = List.of(ftsOnly, shared);

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "tea", 10, false);

    long expectedEvidence = result.candidates().stream()
      .mapToLong(rc -> Tokens.estimate(rc.memory().content()))
      .sum();
    assertThat(store.recordUsageCalls).isEqualTo(1);
    assertThat(store.recordedEvidenceTokens).isEqualTo(expectedEvidence);
    assertThat(store.recordedAnswerTokens).isEqualTo(Tokens.estimate(result.answer()));
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
    assertThat(debug.diagnostics().channels()).hasSize(6); // five primary channels + graph wave

    RecallResult concise = service(store, new FakeModelGateway()).recall("p", "tea", 10, false);
    assertThat(concise.diagnostics()).isNull();
  }

  @Test
  void graphWaveSurfacesConnectedMemoryThroughFusion() {
    // FTS finds "we use redis"; the graph wave, seeded from the query entity "redis", surfaces a
    // connected memory that no other channel matched.
    Memory ftsHit = mem("m1", "we use redis", MemoryType.FACT, null, T0);
    Memory graphHit = mem("g1", "redis runs on the staging cluster", MemoryType.FACT, null, T0.plusSeconds(5));
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(ftsHit);
    store.graphSeedEntities = List.of(new Entity("e-redis", "prof-1", "concept", "redis", "{}", T0));
    store.graphMemories = List.of(graphHit);

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "redis", 10, false);

    assertThat(result.candidates()).extracting(rc -> rc.memory().id()).contains("g1");
    RecallCandidate g = result.candidates().stream()
      .filter(c -> c.memory().id().equals("g1")).findFirst().orElseThrow();
    assertThat(g.source()).contains("graph");
  }

  @Test
  void graphWaveFailureYieldsPartialResultsWithoutFailingRecall() {
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(mem("m1", "a fact", MemoryType.FACT, null, T0));
    store.graphError = new RuntimeException("graph store down");

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "tea", 10, true);

    // The graph channel is non-critical: recall still returns the FTS hit; graph is marked failed.
    assertThat(result.candidates()).extracting(rc -> rc.memory().id()).containsExactly("m1");
    assertThat(result.diagnostics().channels()).anySatisfy(d -> {
      assertThat(d.channel()).isEqualTo(RetrievalChannelType.GRAPH);
      assertThat(d.failed()).isTrue();
    });
  }

  @Test
  void unknownProfileIsNotFound() {
    FakeStore store = new FakeStore();
    store.profile = null;
    assertThatThrownBy(() -> service(store, new FakeModelGateway()).recall("ghost", "tea", 10, false))
      .isInstanceOf(NotFoundException.class);
  }

  @Test
  void fastRecallSkipsModelAnalysisAndSynthesis() {
    Memory shared = mem("m1", "user prefers tea", MemoryType.FACT, "user.drink", T0);
    FakeStore store = new FakeStore();
    store.exactKey = List.of(shared);
    store.ftsMemory = List.of(shared);

    ProbeGateway model = new ProbeGateway();
    RecallResult result = service(store, model).recall("p", "tea", 10, false, true);

    // Fast path returns the retrieved memories with no synthesized answer, and never touches the
    // model for query analysis or synthesis (the expensive calls).
    assertThat(result.answer()).isNull();
    assertThat(result.candidates()).extracting(c -> c.memory().id()).contains("m1");
    assertThat(result.temporalFacts()).isEmpty();
    assertThat(model.analyzeQueryCalled).isFalse();
    assertThat(model.synthesizeCalled).isFalse();
  }

  @Test
  void nonFastRecallStillAnalysesAndSynthesises() {
    Memory shared = mem("m1", "user prefers tea", MemoryType.FACT, "user.drink", T0);
    FakeStore store = new FakeStore();
    store.exactKey = List.of(shared);
    store.ftsMemory = List.of(shared);

    ProbeGateway model = new ProbeGateway();
    RecallResult result = service(store, model).recall("p", "tea", 10, false, false);

    assertThat(result.answer()).isNotNull();
    assertThat(model.analyzeQueryCalled).isTrue();
    assertThat(model.synthesizeCalled).isTrue();
  }

  @Test
  void fastRecallExcludesCodeIndexedMemories() {
    Memory codeFact = new Memory("c1", CodeIndexingService.CODE_SESSION, MemoryType.FACT,
      "Source file Tokens.java defines class Tokens", "code:file:Tokens.java", null, false,
      "{\"source\":\"code\"}", null, T0);
    Memory designFact = mem("m1", "inference cost is estimated per tier from prompt/completion tokens",
      MemoryType.FACT, "cost.estimate", T0.plusSeconds(10));
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(codeFact, designFact);

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "cost", 10, false, true);

    // The code-indexer memory is dropped from the injection path; the conversational one survives.
    assertThat(result.candidates()).extracting(c -> c.memory().id())
      .contains("m1")
      .doesNotContain("c1");
  }

  @Test
  void nonFastRecallKeepsCodeIndexedMemories() {
    Memory codeFact = new Memory("c1", CodeIndexingService.CODE_SESSION, MemoryType.FACT,
      "Source file Tokens.java defines class Tokens", "code:file:Tokens.java", null, false,
      "{\"source\":\"code\"}", null, T0);
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(codeFact);

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "tokens", 10, false, false);

    // A normal (synthesized) recall still surfaces code memories — the filter is injection-only.
    assertThat(result.candidates()).extracting(c -> c.memory().id()).contains("c1");
  }

  @Test
  void codeGraphEvidenceReachesResultAndSynthesis() {
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(mem("m1", "gpt is the forward pass", MemoryType.FACT, null, T0));
    CodeIndexStore codeStore = new EvidenceOnlyCodeIndexStore(
      new CodeSymbol("sym-gpt", "prof-1", "f1", CodeSymbolKind.METHOD, "gpt", "Model#gpt",
        "gpt()", "public", 1, 2, "java", null, "Model.java"),
      new CodeIndexStore.EdgeEvidence(
        new CodeEdge("e1", "prof-1", "sym-main", CodeRelation.CALLS, EdgeConfidence.RESOLVED,
          "sym-gpt", "gpt", "f2"),
        new CodeSymbol("sym-main", "prof-1", "f2", CodeSymbolKind.METHOD, "main", "JGPT#main",
          "main()", "public", 1, 2, "java", null, "JGPT.java"),
        new CodeSymbol("sym-gpt", "prof-1", "f1", CodeSymbolKind.METHOD, "gpt", "Model#gpt",
          "gpt()", "public", 1, 2, "java", null, "Model.java")));

    ProbeGateway model = new ProbeGateway();
    RecallResult result = serviceWithCodeGraph(store, model, codeStore).recall("p", "gpt", 10, false);

    assertThat(result.graphEvidence()).hasSize(1);
    assertThat(result.graphEvidence().getFirst().render())
      .isEqualTo("JGPT#main (JGPT.java) calls Model#gpt (Model.java) [resolved]");
    assertThat(model.synthesizedGraphEvidence).isEqualTo(result.graphEvidence());
    assertThat(result.answer()).contains("1 code edge(s)");
  }

  @Test
  void fastRecallCollectsEvidenceButSkipsSynthesis() {
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(mem("m1", "gpt is the forward pass", MemoryType.FACT, null, T0));
    CodeIndexStore codeStore = new EvidenceOnlyCodeIndexStore(
      new CodeSymbol("sym-gpt", "prof-1", "f1", CodeSymbolKind.METHOD, "gpt", "Model#gpt",
        "gpt()", "public", 1, 2, "java", null, "Model.java"),
      new CodeIndexStore.EdgeEvidence(
        new CodeEdge("e1", "prof-1", "sym-main", CodeRelation.CALLS, EdgeConfidence.RESOLVED,
          "sym-gpt", "gpt", "f2"),
        new CodeSymbol("sym-main", "prof-1", "f2", CodeSymbolKind.METHOD, "main", "JGPT#main",
          "main()", "public", 1, 2, "java", null, "JGPT.java"),
        null));

    ProbeGateway model = new ProbeGateway();
    RecallResult result = serviceWithCodeGraph(store, model, codeStore).recall("p", "gpt", 10, false, true);

    assertThat(result.answer()).isNull();
    assertThat(model.synthesizeCalled).isFalse();
  }

  /** Records whether the model-driven analysis/synthesis stages were invoked, delegating otherwise. */
  private static final class ProbeGateway extends FakeModelGateway {
    boolean analyzeQueryCalled = false;
    boolean synthesizeCalled = false;
    List<dev.alvo.pieria.retrieval.model.GraphEvidence> synthesizedGraphEvidence;

    @Override
    public QueryAnalysis analyzeQuery(String query) {
      analyzeQueryCalled = true;
      return super.analyzeQuery(query);
    }

    @Override
    public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
      synthesizeCalled = true;
      return super.synthesizeRecall(query, candidates);
    }

    @Override
    public String synthesizeRecall(String query, List<RecallCandidate> candidates,
                                   List<dev.alvo.pieria.retrieval.model.TemporalFact> temporalFacts) {
      synthesizeCalled = true;
      return super.synthesizeRecall(query, candidates, temporalFacts);
    }

    @Override
    public String synthesizeRecall(String query, List<RecallCandidate> candidates,
                                   List<dev.alvo.pieria.retrieval.model.TemporalFact> temporalFacts,
                                   List<dev.alvo.pieria.retrieval.model.GraphEvidence> graphEvidence) {
      synthesizeCalled = true;
      synthesizedGraphEvidence = graphEvidence;
      return super.synthesizeRecall(query, candidates, temporalFacts, graphEvidence);
    }
  }

  /**
   * A code index holding one seed symbol and one edge: enough for the code-graph wave to emit
   * evidence without resolving derived memories (the neighborhood is empty on purpose).
   */
  private static final class EvidenceOnlyCodeIndexStore implements CodeIndexStore {
    private final CodeSymbol seed;
    private final EdgeEvidence edge;

    EvidenceOnlyCodeIndexStore(CodeSymbol seed, EdgeEvidence edge) {
      this.seed = seed;
      this.edge = edge;
    }

    @Override
    public boolean isCodeIndexPresent(String profileId) {
      return true;
    }

    @Override
    public List<CodeSymbol> findSymbolsByName(String profileId, List<String> names, int limit) {
      return names.contains(seed.name()) ? List.of(seed) : List.of();
    }

    @Override
    public List<EdgeEvidence> findEdgesTouching(
      String profileId, List<String> symbolIds, EdgeConfidence minConfidence, int limit) {
      return symbolIds.contains(seed.id()) ? List.of(edge) : List.of();
    }

    // --- unused surface for the evidence tests ---
    @Override public dev.alvo.pieria.domain.code.CodeModule upsertCodeModule(String p, dev.alvo.pieria.domain.code.CodeModule m) { return m; }
    @Override public dev.alvo.pieria.domain.code.CodeFile upsertCodeFile(String p, dev.alvo.pieria.domain.code.CodeFile f) { return f; }
    @Override public CodeSymbol upsertCodeSymbol(String p, CodeSymbol s) { return s; }
    @Override public CodeEdge upsertCodeEdge(String p, CodeEdge e) { return e; }
    @Override public Optional<String> fileContentHash(String p, String path) { return Optional.empty(); }
    @Override public void replaceFileIndex(String p, dev.alvo.pieria.domain.code.CodeFile f, List<CodeSymbol> s, List<CodeEdge> e) { }
    @Override public List<CodeSymbol> searchSymbolsFts(String p, String q, int l) { return List.of(); }
    @Override public List<CodeSymbol> findSymbolsByQualifiedName(String p, List<String> n, int l) { return List.of(); }
    @Override public List<CodeSymbol> findSymbolsByIds(String p, List<String> ids, int l) { return List.of(); }
    @Override public List<String> symbolNeighborhood(String p, List<String> seeds, int d, int f, EdgeConfidence c) { return List.of(); }
    @Override public CodeIndexCounts counts(String p) { return new CodeIndexCounts(1, 1, 1, 0); }
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
    // Graph wave: empty by default so the graph channel runs cleanly (0 hits, not failed).
    List<Entity> graphSeedEntities = List.of();
    List<Memory> graphMemories = List.of();
    RuntimeException graphError;
    int graphCalls;
    int recordUsageCalls;
    long recordedEvidenceTokens = -1;
    long recordedAnswerTokens = -1;

    @Override
    public Optional<Profile> findProfile(String name) {
      return Optional.ofNullable(profile);
    }

    @Override
    public void recordRecallUsage(String profileId, long evidenceTokens, long answerTokens) {
      recordUsageCalls++;
      recordedEvidenceTokens = evidenceTokens;
      recordedAnswerTokens = answerTokens;
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

    @Override
    public List<Entity> findEntitiesByName(String profileId, List<String> names, int limit) {
      graphCalls++;
      if (graphError != null) {
        throw graphError;
      }
      return graphSeedEntities;
    }

    @Override
    public List<Entity> entitiesForMemories(String profileId, List<String> memoryIds, int limit) {
      return List.of();
    }

    @Override
    public List<String> neighborhood(String profileId, List<String> seedEntityIds, int depth, int fanout) {
      return seedEntityIds; // identity expansion: keep the test focused on fusion, not traversal
    }

    @Override
    public List<Memory> findMemoriesByEntities(String profileId, List<String> entityIds, int limit) {
      return graphMemories;
    }

    // --- unused write/admin surface for the read-path tests ---
    @Override public Profile getOrCreateProfile(String name) { throw new UnsupportedOperationException(); }
    @Override public void insertMessages(String p, String s, List<Message> m) { }
    @Override public Memory insertMemory(String p, Memory m) { throw new UnsupportedOperationException(); }
    @Override public List<Memory> listMemories(String p, MemoryType t, String s, boolean incl) { return List.of(); }
    @Override public boolean forgetMemory(String p, String id) { return false; }
    @Override public List<dev.alvo.pieria.domain.ExportRow> exportProfile(String p) { return List.of(); }
    @Override public List<RecallCandidate> findRecallCandidates(String p, String q, int l) { return List.of(); }
  }
}
