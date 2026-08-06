package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.config.VerifyMode;

import dev.alvo.pieria.api.request.RecallMode;
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
import dev.alvo.pieria.tools.TextSimilarity;
import dev.alvo.pieria.tools.Tokens;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    return new PieriaProperties.Retrieval(true, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 0.0, 0.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.60, 0.78);
  }

  private static PieriaProperties props() {
    return new PieriaProperties(null, null, null, null,
      new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS,
        1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70), retrievalCfg(), null);
  }

  private RetrievalService service(MemoryStore store, FakeModelGateway model) {
    return new RetrievalService(store, model, new DeterministicQueryAnalyzer(), new NoOpCodeIndexStore(),
      EffectiveConfigResolver.withoutOverrides(props()));
  }

  /** As {@link #service}, but with the code-graph wave enabled over the given code-index store. */
  private RetrievalService serviceWithCodeGraph(MemoryStore store, FakeModelGateway model, CodeIndexStore codeStore) {
    PieriaProperties.Retrieval cfg = new PieriaProperties.Retrieval(
      true, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 0.0, 1.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.60, 0.78);
    PieriaProperties props = new PieriaProperties(null, null, null, null,
      new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS,
        1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70), cfg, null);
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
    assertThat(store.recordedSourceTokens).isEqualTo(expectedEvidence);
    assertThat(store.recordedAnswerTokens).isEqualTo(Tokens.estimate(result.answer()));
  }

  @Test
  void nonSynthesizingRecallChargesTheServedMemoryPayload() {
    Memory shared = mem("m1", "user prefers tea", MemoryType.FACT, "user.drink", T0);
    Memory ftsOnly = mem("m2", "tea is grown in Assam", MemoryType.FACT, null, T0.plusSeconds(10));
    FakeStore store = new FakeStore();
    store.exactKey = List.of(shared);
    store.ftsMemory = List.of(ftsOnly, shared);

    // EVIDENCE tier: no synthesized answer, so what the caller received is the raw memory contents.
    RecallResult result = service(store, new FakeModelGateway())
      .recall("p", "tea", 10, false, RecallMode.EVIDENCE);

    assertThat(result.answer()).isNull();
    long servedPayload = result.candidates().stream()
      .mapToLong(rc -> Tokens.estimate(rc.memory().content()))
      .sum();
    assertThat(servedPayload).isPositive();
    assertThat(store.recordUsageCalls).isEqualTo(1);
    // The served payload is charged, not zero — otherwise this tier would bank the full source.
    assertThat(store.recordedAnswerTokens).isEqualTo(servedPayload);
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
  void unknownProfileReturnsEmptyResult() {
    FakeStore store = new FakeStore();
    store.profile = null;
    RecallResult result = service(store, new FakeModelGateway()).recall("ghost", "tea", 10, false);
    assertThat(result.candidates()).isEmpty();
    assertThat(result.answer()).isNull();
  }

  @Test
  void evidenceModeSkipsModelAnalysisAndSynthesis() {
    Memory shared = mem("m1", "user prefers tea", MemoryType.FACT, "user.drink", T0);
    FakeStore store = new FakeStore();
    store.exactKey = List.of(shared);
    store.ftsMemory = List.of(shared);

    ProbeGateway model = new ProbeGateway();
    RecallResult result = service(store, model).recall("p", "tea", 10, false, RecallMode.EVIDENCE);

    // The EVIDENCE tier returns the retrieved memories with no synthesized answer, and never touches
    // the model for query analysis or synthesis (the expensive calls).
    assertThat(result.answer()).isNull();
    assertThat(result.candidates()).extracting(c -> c.memory().id()).contains("m1");
    assertThat(result.temporalFacts()).isEmpty();
    assertThat(model.analyzeQueryCalled).isFalse();
    assertThat(model.synthesizeCalled).isFalse();
  }

  @Test
  void synthesizedModeAnalysesAndSynthesises() {
    Memory shared = mem("m1", "user prefers tea", MemoryType.FACT, "user.drink", T0);
    FakeStore store = new FakeStore();
    store.exactKey = List.of(shared);
    store.ftsMemory = List.of(shared);

    ProbeGateway model = new ProbeGateway();
    RecallResult result = service(store, model).recall("p", "tea", 10, false, RecallMode.SYNTHESIZED);

    assertThat(result.answer()).isNotNull();
    assertThat(model.analyzeQueryCalled).isTrue();
    assertThat(model.synthesizeCalled).isTrue();
  }

  @Test
  void injectionPathExcludesCodeIndexedMemories() {
    Memory codeFact = new Memory("c1", CodeIndexingService.CODE_SESSION, MemoryType.FACT,
      "Source file Tokens.java defines class Tokens", "code:file:Tokens.java", null, false,
      "{\"source\":\"code\"}", null, T0);
    Memory designFact = mem("m1", "inference cost is estimated per tier from prompt/completion tokens",
      MemoryType.FACT, "cost.estimate", T0.plusSeconds(10));
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(codeFact, designFact);

    // excludeCodeDerived=true is the injection path (text/plain context endpoint).
    RecallResult result = service(store, new FakeModelGateway())
      .recall("p", "cost", 10, false, RecallMode.EVIDENCE, true);

    // The code-indexer memory is dropped from the injection path; the conversational one survives.
    assertThat(result.candidates()).extracting(c -> c.memory().id())
      .contains("m1")
      .doesNotContain("c1");
  }

  @Test
  void evidenceModeAloneKeepsCodeIndexedMemories() {
    Memory codeFact = new Memory("c1", CodeIndexingService.CODE_SESSION, MemoryType.FACT,
      "Source file Tokens.java defines class Tokens", "code:file:Tokens.java", null, false,
      "{\"source\":\"code\"}", null, T0);
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(codeFact);

    // The EVIDENCE tier without excludeCodeDerived still surfaces code memories — the filter is
    // decoupled from the tier and is an injection-only concern.
    RecallResult result = service(store, new FakeModelGateway())
      .recall("p", "tokens", 10, false, RecallMode.EVIDENCE, false);

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

  // The observed failure: one fact stored six times under drifted topic keys filled the whole
  // ten-slot primer. Texts are the real ones from the profile that produced it.
  @Test
  void collapsesNearDuplicateResultsSoTheLimitYieldsDistinctMemories() {
    String base = "The Pieria daemon MCP is the primary long-term knowledge base for durable facts, "
      + "preferences, project context, recurring workflows, decisions, environment details, and ";
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(
      mem("d1", base + "other information likely to help in future sessions.", MemoryType.FACT, "pieria.mcp", T0),
      mem("d2", base + "other information to help in future sessions.", MemoryType.FACT, "pieria.usage", T0),
      mem("d3", "The Pieria daemon MCP is treated as the primary long-term knowledge base for durable "
        + "facts, preferences, project context, recurring workflows, decisions, environment details, "
        + "and other information likely to help in future sessions.", MemoryType.FACT, "pieria.role", T0),
      mem("real", "The embedding dimension is fixed at 1024 and cannot change without re-embedding.",
        MemoryType.FACT, "embedding.dimension", T0));

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "pieria", 2, false);

    // Without the collapse the two slots would both be restatements and "real" would never surface.
    assertThat(result.candidates()).hasSize(2);
    assertThat(result.candidates().get(0).memory().id()).isEqualTo("d1");
    assertThat(result.candidates().get(1).memory().id()).isEqualTo("real");
  }

  @Test
  void collapseKeepsTheHighestRankedOfADuplicateGroup() {
    Memory weak = mem("weak", "The daemon binds a REST API on 127.0.0.1 in local mode.",
      MemoryType.FACT, "bind.a", T0);
    Memory strong = mem("strong", "The daemon binds a REST API on 127.0.0.1 in local mode and never "
      + "exposes a public interface.", MemoryType.FACT, "bind.b", T0);
    FakeStore store = new FakeStore();
    store.exactKey = List.of(strong);           // weight 3.0 — outranks the FTS-only hit
    store.ftsMemory = List.of(weak, strong);

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "daemon", 10, false);

    assertThat(result.candidates()).singleElement()
      .extracting(c -> c.memory().id()).isEqualTo("strong");
  }

  @Test
  void collapseLeavesDistinctMemoriesAlone() {
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(
      mem("m1", "The daemon is the only writer to the embedded SQLite store.", MemoryType.FACT, "a", T0),
      mem("m2", "Flyway runs the schema migrations at daemon startup.", MemoryType.FACT, "b", T0),
      mem("m3", "Tasks are excluded from the vector index to keep it lean.", MemoryType.FACT, "c", T0));

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "daemon", 10, false);

    assertThat(result.candidates()).hasSize(3);
  }

  // The case lexical collapse structurally cannot see: one fact carried by different words. These
  // three are the real module-layout memories from this repository's own profile, which score
  // 0.03-0.08 on shingle-Jaccard — far under any usable lexical threshold — but 0.76-0.83 by cosine.
  @Test
  void collapsesParaphrasesThatShareNoWordTrigrams() {
    Memory a = mem("a", "The repository is organized into Gradle modules under `modules/`: `shared` "
      + "for HTTP DTOs, `daemon` for REST controllers and storage.", MemoryType.FACT, "repository.structure", T0);
    Memory b = mem("b", "The project repository is split into Gradle modules located under the "
      + "modules/ directory, with modules named shared, daemon, gateway, cli, and eval.",
      MemoryType.FACT, "project.layout.gradle.modules", T0);
    Memory distinct = mem("distinct", "Flyway runs the schema migrations at daemon startup.",
      MemoryType.FACT, "migrations", T0);

    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(a, b, distinct);
    store.embeddings = Map.of(
      "a", new float[] {1.0f, 0.0f, 0.0f},
      "b", new float[] {0.95f, 0.31f, 0.0f},     // cosine(a,b) ≈ 0.95 — a paraphrase
      "distinct", new float[] {0.0f, 0.0f, 1.0f}); // orthogonal to both

    // Guard the premise: these two are invisible to the lexical measure this test is about.
    assertThat(TextSimilarity.similarity(a.content(), b.content())).isLessThan(0.20);

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "modules", 10, false);

    assertThat(result.candidates()).extracting(c -> c.memory().id())
      .containsExactly("a", "distinct");
  }

  @Test
  void semanticCollapseLeavesMerelyRelatedMemoriesAlone() {
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(
      mem("m1", "The daemon is the only writer to the embedded SQLite store.", MemoryType.FACT, "a", T0),
      mem("m2", "Flyway runs the schema migrations at daemon startup.", MemoryType.FACT, "b", T0));
    // Related but distinct — 0.70 is below the 0.78 default and must not collapse.
    store.embeddings = Map.of(
      "m1", new float[] {1.0f, 0.0f},
      "m2", new float[] {0.70f, 0.714f});

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "daemon", 10, false);

    assertThat(result.candidates()).hasSize(2);
  }

  @Test
  void collapseFallsBackToLexicalWhenEmbeddingsAreUnavailable() {
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(
      mem("d1", "The daemon binds a REST API on 127.0.0.1 in local mode.", MemoryType.FACT, "x", T0),
      mem("d2", "The daemon binds a REST API on 127.0.0.1 in local mode.", MemoryType.FACT, "y", T0));
    store.embeddingsError = new IllegalStateException("vector column unavailable");

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "daemon", 10, false);

    // The recall still succeeds, and the lexically identical pair still collapses.
    assertThat(result.candidates()).singleElement()
      .extracting(c -> c.memory().id()).isEqualTo("d1");
  }

  // Code-index summaries are templated, so two describing different files look near-identical.
  // Collapsing them would hide a genuine result — by cosine they score higher (0.85-0.90 measured)
  // than any genuine duplicate, so the exemption has to cover the semantic half too.
  @Test
  void collapseExemptsCodeIndexerMemoriesFromSemanticCollapseToo() {
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(
      new Memory("c1", CodeIndexingService.CODE_SESSION, MemoryType.FACT,
        "Source file onboarding/TextDiscovery.java (java) defines: class Doc, method scan.",
        "code:file:onboarding/TextDiscovery.java", null, false, "{}", null, T0),
      new Memory("c2", CodeIndexingService.CODE_SESSION, MemoryType.FACT,
        "Source file onboarding/PdfDiscovery.java (java) defines: class Doc, method parse.",
        "code:file:onboarding/PdfDiscovery.java", null, false, "{}", null, T0));
    store.embeddings = Map.of(
      "c1", new float[] {1.0f, 0.0f},
      "c2", new float[] {0.999f, 0.045f});   // ≈ 0.999 — would collapse if not exempt

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "discovery", 10, false);

    assertThat(result.candidates()).hasSize(2);
  }

  // Code-index summaries are templated, so two describing different files look near-identical.
  // Collapsing them would hide a genuine result.
  @Test
  void collapseExemptsCodeIndexerMemories() {
    String shape = "(java) defines: class Doc, class Discovery, method scan, method parse, method emit.";
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(
      new Memory("c1", CodeIndexingService.CODE_SESSION, MemoryType.FACT,
        "Source file onboarding/TextDiscovery.java " + shape, "code:file:onboarding/TextDiscovery.java",
        null, false, "{}", null, T0),
      new Memory("c2", CodeIndexingService.CODE_SESSION, MemoryType.FACT,
        "Source file onboarding/PdfDiscovery.java " + shape, "code:file:onboarding/PdfDiscovery.java",
        null, false, "{}", null, T0));

    RecallResult result = service(store, new FakeModelGateway()).recall("p", "discovery", 10, false);

    assertThat(result.candidates()).hasSize(2);
  }

  @Test
  void evidenceModeCollectsEvidenceButSkipsSynthesis() {
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
    RecallResult result = serviceWithCodeGraph(store, model, codeStore)
      .recall("p", "gpt", 10, false, RecallMode.EVIDENCE);

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
    long recordedSourceTokens = -1;
    long recordedAnswerTokens = -1;
    Map<String, float[]> embeddings = Map.of();
    RuntimeException embeddingsError;

    @Override
    public Optional<Profile> findProfile(String name) {
      return Optional.ofNullable(profile);
    }

    @Override
    public Map<String, float[]> embeddingsFor(String profileId, Collection<String> memoryIds) {
      if (embeddingsError != null) {
        throw embeddingsError;
      }
      Map<String, float[]> out = new LinkedHashMap<>();
      for (String id : memoryIds) {
        float[] vector = embeddings.get(id);
        if (vector != null) {
          out.put(id, vector);
        }
      }
      return out;
    }

    @Override
    public void recordRecallUsage(String profileId, long sourceTokens, long answerTokens) {
      recordUsageCalls++;
      recordedSourceTokens = sourceTokens;
      recordedAnswerTokens = answerTokens;
    }

    // This fake has no separate provenance/source-token model: it stands in for a backend where
    // each memory's source is its own content, mirroring the real store's remember-path default.
    @Override
    public long sumActiveSourceTokens(String profileId, java.util.Collection<String> memoryIds) {
      if (memoryIds == null || memoryIds.isEmpty()) {
        return 0L;
      }
      java.util.Map<String, Memory> byId = new java.util.LinkedHashMap<>();
      java.util.stream.Stream.of(exactKey, ftsMemory, ftsMessage, vectorHits, graphMemories)
        .flatMap(List::stream)
        .forEach(m -> byId.putIfAbsent(m.id(), m));
      return memoryIds.stream()
        .distinct()
        .map(byId::get)
        .filter(java.util.Objects::nonNull)
        .mapToLong(m -> Tokens.estimate(m.content()))
        .sum();
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
