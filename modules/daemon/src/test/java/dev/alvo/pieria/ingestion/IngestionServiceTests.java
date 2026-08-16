package dev.alvo.pieria.ingestion;

import dev.alvo.pieria.config.VerifyMode;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.ingestion.model.Chunk;
import dev.alvo.pieria.ingestion.model.OutboxEntry;
import dev.alvo.pieria.ingestion.model.UnifiedCandidate;
import dev.alvo.pieria.ingestion.model.VerificationResult;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level test of the full ingestion pipeline driven by the deterministic
 * {@link FakeModelGateway} (no network) against a real SQLite-backed {@link SqliteMemoryStore}.
 * Relies on the gateway's documented content sentinels (UNSUPPORTED → drop, TASK → task type).
 */
class IngestionServiceTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;
  private SqliteMemoryStore store;
  private IngestionService service;

  private static PieriaProperties props() {
    return props(VerifyMode.ALWAYS);
  }

  private static PieriaProperties props(VerifyMode verifyMode) {
    return new PieriaProperties(null, null, null,
      new PieriaProperties.Model("small", "large", "embed", 1024, 4, null, null),
      new PieriaProperties.Ingestion(10000, 2, 4, verifyMode,
        1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70),
      null,
      null);
  }

  private static Message msg(String role, String content) {
    return new Message(null, null, role, content, null);
  }

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-ingest-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(dataSource).load().migrate();

    jdbc = JdbcClient.create(dataSource);
    store = new SqliteMemoryStore(jdbc);
    TranscriptNormalizer normalizer = new TranscriptNormalizer();
    Chunker chunker = new Chunker(normalizer);
    service = new IngestionService(store, new FakeModelGateway(), normalizer, chunker,
      EffectiveConfigResolver.withoutOverrides(props()));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (dataSource != null) {
      dataSource.close();
    }
    if (dbFile != null) {
      Files.deleteIfExists(dbFile);
      Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-wal"));
      Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-shm"));
    }
  }

  @Test
  void storedMemoriesCarryWhenTheSourceTurnWasSpoken() {
    Instant spokenAt = Instant.parse("2023-05-25T13:14:00Z");

    List<Memory> stored = service.ingest("proj", "s1", List.of(
      new Message(null, null, "user", "I love coffee", spokenAt),
      new Message(null, null, "assistant", "noted", spokenAt)));

    // Retrieval anchors a memory's relative references on this, so it must be the speaking time —
    // not the ingest time, which for a back-filled transcript is years out.
    assertTrue(stored.getFirst().payload().contains("\"stated_at\":\"2023-05-25T13:14:00Z\""),
      "payload should record the speaking time, was: " + stored.getFirst().payload());
  }

  @Test
  void untimestampedTurnsFallBackToTheIngestTime() {
    Instant before = Instant.now();

    List<Memory> stored = service.ingest("proj", "s1",
      List.of(msg("user", "I love coffee"), msg("assistant", "noted")));

    // A caller supplying no timestamps is declaring the conversation is happening now — the same
    // fallback TranscriptNormalizer used when it rewrote this transcript's relative dates.
    String payload = stored.getFirst().payload();
    assertTrue(payload.contains("\"stated_at\":"), "payload should still record a time: " + payload);
    Instant statedAt = Instant.parse(payload.replaceAll(".*\"stated_at\":\"([^\"]+)\".*", "$1"));
    assertFalse(statedAt.isBefore(before), "fallback should be the ingest time");
  }

  @Test
  void ingestStoresVerifiedFactWithEmbedText() {
    List<Memory> stored = service.ingest("proj", "s1",
      List.of(msg("user", "I love coffee"), msg("assistant", "noted")));

    assertEquals(1, stored.size());
    Memory m = stored.getFirst();
    assertEquals(MemoryType.FACT, m.type());
    assertTrue(m.content().contains("I love coffee"));
    // embed_text prepends interrogative queries to the declarative content.
    assertTrue(m.embedText().startsWith("what is "), "embed_text should lead with interrogatives");
    assertTrue(m.embedText().endsWith(m.content()), "embed_text should end with the content");

    // A vector job was enqueued for the fact.
    List<OutboxEntry> outbox = store.drainOutbox(10);
    assertEquals(1, outbox.size());
    assertEquals(m.id(), outbox.getFirst().memoryId());
  }

  @Test
  void ingestRecordsCompressionUsage() {
    service.ingest("proj", "s1", List.of(msg("user", "I love coffee"), msg("assistant", "noted")));

    var usage = store.usageStats(store.findProfile("proj").orElseThrow().id());
    assertEquals(1, usage.ingestCount());
    assertTrue(usage.tokensIngested() > 0, "raw-message tokens should be recorded");
    assertTrue(usage.tokensStored() > 0, "distilled-memory tokens should be recorded");
  }

  @Test
  void ingestAttributesChunkSourceTokensAcrossItsMemories() {
    FakeModelGateway gateway = new FakeModelGateway() {
      @Override
      public List<UnifiedCandidate> extractUnified(Chunk chunk) {
        return List.of(
          new UnifiedCandidate("first distilled fact", classify("first distilled fact"),
            chunk.index(), "extract", GraphFragment.empty()),
          new UnifiedCandidate("second distilled fact", classify("second distilled fact"),
            chunk.index(), "extract", GraphFragment.empty()),
          new UnifiedCandidate("third distilled fact", classify("third distilled fact"),
            chunk.index(), "extract", GraphFragment.empty()));
      }
    };

    // One user message of 120 chars -> the transcript's source tokens for this ingest.
    String raw = "z".repeat(120);
    List<Memory> stored = service(gateway, VerifyMode.ALWAYS)
      .ingest("proj", "pieria-init", List.of(msg("user", raw)));

    assertEquals(3, stored.size());

    long ingestedTokens = jdbc.sql("SELECT tokens_ingested FROM profile_usage WHERE profile_id = ?")
      .param(store.findProfile("proj").orElseThrow().id())
      .query(Long.class)
      .single();
    long attributed = jdbc.sql("SELECT COALESCE(SUM(source_tokens), 0) FROM memories WHERE profile_id = ?")
      .param(store.findProfile("proj").orElseThrow().id())
      .query(Long.class)
      .single();

    // Every memory carries a positive, equal slice, and no memory carries the whole chunk.
    List<Long> slices = jdbc.sql("SELECT source_tokens FROM memories WHERE profile_id = ? ORDER BY id")
      .param(store.findProfile("proj").orElseThrow().id())
      .query(Long.class)
      .list();
    assertEquals(3, slices.size());
    slices.forEach(s -> assertTrue(s > 0, "expected a positive source slice, got " + s));
    assertEquals(1, slices.stream().distinct().count(), "slices should be equal across the chunk");

    // The invariant: attributed source never exceeds what was actually fed in (allowing for the
    // per-memory ceil rounding, at most one extra token per memory).
    assertTrue(attributed <= ingestedTokens + slices.size(),
      "attributed " + attributed + " must not exceed ingested " + ingestedTokens);
  }

  @Test
  void overlappingChunksAttributeEachMessageOnlyOnce() {
    // One distinct memory per chunk, so each chunk's whole source slice lands on a single row.
    FakeModelGateway gateway = new FakeModelGateway() {
      @Override
      public List<UnifiedCandidate> extractUnified(Chunk chunk) {
        String content = "c" + chunk.index() + "fact distilled from its chunk";
        return List.of(new UnifiedCandidate(content, classify(content), chunk.index(),
          "extract", GraphFragment.empty()));
      }
    };

    // chunk-size-chars=10000 fits two ~4500-char messages per chunk; with
    // chunk-overlap-messages=2 the chunker can only advance one message at a time, so six
    // messages yield five deliberately overlapping chunks (each message appears in two of them).
    List<Message> transcript = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      transcript.add(msg(i % 2 == 0 ? "user" : "assistant", ("m" + i + " ").repeat(1500)));
    }

    List<Memory> stored = service(gateway, VerifyMode.ALWAYS).ingest("proj", "overlap", transcript);
    assertTrue(stored.size() >= 3,
      "expected at least 3 overlapping chunks, got " + stored.size() + " memories");

    String profileId = store.findProfile("proj").orElseThrow().id();
    long ingestedTokens = jdbc.sql("SELECT tokens_ingested FROM profile_usage WHERE profile_id = ?")
      .param(profileId)
      .query(Long.class)
      .single();
    long attributed = jdbc.sql("SELECT COALESCE(SUM(source_tokens), 0) FROM memories WHERE profile_id = ?")
      .param(profileId)
      .query(Long.class)
      .single();
    long memoryCount = jdbc.sql("SELECT COUNT(*) FROM memories WHERE profile_id = ?")
      .param(profileId)
      .query(Long.class)
      .single();

    // The invariant that makes the savings metric defensible: overlapped messages are attributed
    // to the first chunk that contains them, never once per chunk. Slack is the per-memory ceil
    // rounding only (at most one token each).
    assertTrue(attributed <= ingestedTokens + memoryCount,
      "attributed " + attributed + " must not exceed ingested " + ingestedTokens
        + " (+" + memoryCount + " rounding slack)");
  }

  @Test
  void unsupportedCandidateIsDropped() {
    List<Memory> stored = service.ingest("proj", "s1",
      List.of(msg("user", "this claim is UNSUPPORTED by anything")));
    assertTrue(stored.isEmpty(), "verification should drop the UNSUPPORTED candidate");
  }

  @Test
  void taskMemoryIsStoredButNotEnqueued() {
    List<Memory> stored = service.ingest("proj", "s1",
      List.of(msg("user", "TASK finish the report")));
    assertEquals(1, stored.size());
    assertEquals(MemoryType.TASK, stored.getFirst().type());
    assertTrue(store.drainOutbox(10).isEmpty(), "tasks must not be enqueued for vectorization");
  }

  @Test
  void reingestIsIdempotent() {
    List<Message> conversation = List.of(msg("user", "I love coffee"), msg("assistant", "noted"));
    Profile p = store.getOrCreateProfile("proj");

    service.ingest("proj", "s1", conversation);
    int afterFirst = store.listMemories(p.id(), null, null).size();
    service.ingest("proj", "s1", conversation);
    int afterSecond = store.listMemories(p.id(), null, null).size();

    assertEquals(afterFirst, afterSecond, "re-ingesting the same transcript must not duplicate or lose memories");
    assertEquals(1, afterSecond);
  }

  @Test
  void blankMessagesYieldNoMemories() {
    List<Memory> stored = service.ingest("proj", "s1",
      List.of(msg("user", "   "), msg("", "x")));
    assertTrue(stored.isEmpty());
  }

  @Test
  void ingestPersistsGraphEntitiesAndEdges() {
    // FakeModelGateway.extractGraph turns the first two tokens of the verified content into two
    // entities joined by one edge.
    service.ingest("proj", "s1", List.of(msg("user", "redis powers sessions")));

    long entities = jdbc.sql("SELECT COUNT(*) FROM entities").query(Long.class).single();
    long edges = jdbc.sql("SELECT COUNT(*) FROM edges").query(Long.class).single();
    assertTrue(entities >= 2, "graph entities should be persisted during ingestion");
    assertTrue(edges >= 1, "a graph edge should be persisted during ingestion");
  }

  @Test
  void graphExtractionFailureStillCommitsTheMemory() {
    // The FAILGRAPH sentinel makes extractGraph throw; the memory write must still commit, with no
    // graph rows (degradable: graph extraction never fails ingestion).
    List<Memory> stored = service.ingest("proj", "s1",
      List.of(msg("user", "FAILGRAPH should not break ingestion")));

    assertEquals(1, stored.size());
    long edges = jdbc.sql("SELECT COUNT(*) FROM edges").query(Long.class).single();
    assertEquals(0L, edges, "a graph extraction failure must leave no edges but keep the memory");
  }

  @Test
  void deferredModePersistsGroundedUnifiedGraphWithoutASeparateGraphCall() {
    AtomicInteger graphCalls = new AtomicInteger();
    GraphFragment fragment = new GraphFragment(
      List.of(Entity.of("tool", "redis", "{}"), Entity.of("concept", "sessions", "{}")),
      List.of(new GraphFragment.EdgeTriple("redis", "tool", "powers", "sessions", "concept")));
    FakeModelGateway gateway = new FakeModelGateway() {
      @Override
      public List<UnifiedCandidate> extractUnified(Chunk chunk) {
        String content = "redis powers sessions";
        return List.of(new UnifiedCandidate(content, classify(content), chunk.index(), "extract", fragment));
      }

      @Override
      public List<GraphFragment> extractGraphAll(List<String> contents) {
        graphCalls.incrementAndGet();
        return super.extractGraphAll(contents);
      }
    };

    IngestionResult result = service(gateway, VerifyMode.ALWAYS).ingestDetailed("proj", "pieria-init",
      List.of(msg("user", "redis powers sessions")), null, GraphMode.DEFERRED,
      IngestProgressListener.noop());

    assertEquals(1, result.memories().size());
    assertEquals(0, result.graphDeferred());
    assertEquals(0, graphCalls.get());
    assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM edges").query(Long.class).single());
  }

  @Test
  void correctedUnifiedCandidateDiscardsItsStaleGraphAndRemainsAdoptable() {
    GraphFragment fragment = new GraphFragment(
      List.of(Entity.of("concept", "stale", "{}")), List.of());
    FakeModelGateway gateway = new FakeModelGateway() {
      @Override
      public List<UnifiedCandidate> extractUnified(Chunk chunk) {
        String content = "statement with TYPO";
        return List.of(new UnifiedCandidate(content, classify(content), chunk.index(), "extract", fragment));
      }
    };

    IngestionResult result = service(gateway, VerifyMode.ALWAYS).ingestDetailed("proj", "pieria-init",
      List.of(msg("user", "statement with TYPO")), null, GraphMode.DEFERRED,
      IngestProgressListener.noop());

    assertEquals(1, result.graphDeferred());
    assertEquals(0L, jdbc.sql("SELECT COUNT(*) FROM edges").query(Long.class).single());
    assertEquals(1L, store.countGraphOrphans(store.findProfile("proj").orElseThrow().id()));
  }

  @Test
  void taskMemoriesAreNotGraphExtracted() {
    // Tasks are excluded from the graph (as from the vector index): no edges even though the content
    // has two tokens the extractor would otherwise link.
    service.ingest("proj", "s1", List.of(msg("user", "TASK ship redis upgrade")));
    long edges = jdbc.sql("SELECT COUNT(*) FROM edges").query(Long.class).single();
    assertEquals(0L, edges);
  }

  @Test
  void ingestReportsPerPhaseProgress() {
    record Tick(String phase, int done, int total) {
    }
    List<Tick> ticks = new ArrayList<>();
    service.ingest("proj", "s1",
      List.of(msg("user", "I love coffee"), msg("assistant", "noted")),
      (phase, done, total) -> ticks.add(new Tick(phase, done, total)));

    // The pipeline reports its phases each finishing fully complete (done == total). Storage is
    // interleaved into the verify phase (each survivor is classified + stored as it passes), so there
    // is no separate "store" phase.
    for (String phase : List.of("extract", "verify")) {
      Tick last = ticks.stream().filter(t -> t.phase().equals(phase)).reduce((a, b) -> b)
        .orElseThrow(() -> new AssertionError("no progress reported for phase " + phase));
      assertTrue(last.total() >= 1, phase + " total should be positive");
      assertEquals(last.total(), last.done(), phase + " should finish fully complete");
    }
  }

  @Test
  void extractionSamplesRunMultiplePassesButDedupe() {
    // A per-request override of 3 samples must invoke the unified extraction 3× for the single
    // chunk, yet the identical deterministic candidates dedupe down to one stored memory (union
    // semantics).
    AtomicInteger extractCalls = new AtomicInteger();
    FakeModelGateway counting = new FakeModelGateway() {
      @Override
      public List<UnifiedCandidate> extractUnified(Chunk chunk) {
        extractCalls.incrementAndGet();
        return super.extractUnified(chunk);
      }
    };
    IngestionService sampled = service(counting, VerifyMode.ALWAYS);

    // Two messages → one chunk, so calls == samples.
    List<Memory> stored = sampled.ingest("proj", "s1",
      List.of(msg("user", "I love coffee"), msg("assistant", "noted")), 3);

    assertEquals(3, extractCalls.get(), "each of the 3 samples should run one unified extraction");
    assertEquals(1, stored.size(), "identical samples must dedupe to a single stored memory");
  }

  @Test
  void longConversationIngestsWithoutError() {
    List<Message> many = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      many.add(msg(i % 2 == 0 ? "user" : "assistant", "message number " + i));
    }
    List<Memory> stored = service.ingest("proj", "s1", many);
    assertFalse(stored.isEmpty(), "a long conversation should still produce memories");
  }

  /** Gateway whose extraction emits one fixed, pre-classified candidate and counts verify calls. */
  private static class ScriptedGateway extends FakeModelGateway {
    final AtomicInteger verifyCalls = new AtomicInteger();
    final AtomicInteger classifyCalls = new AtomicInteger();
    private final String candidateContent;

    ScriptedGateway(String candidateContent) {
      this.candidateContent = candidateContent;
    }

    @Override
    public List<UnifiedCandidate> extractUnified(Chunk chunk) {
      return List.of(new UnifiedCandidate(candidateContent, super.classify(candidateContent),
        chunk.index(), "extract"));
    }

    @Override
    public VerificationResult verify(String content, String transcript) {
      verifyCalls.incrementAndGet();
      return super.verify(content, transcript);
    }

    @Override
    public dev.alvo.pieria.ingestion.model.Classification classify(String content) {
      classifyCalls.incrementAndGet();
      return super.classify(content);
    }
  }

  private IngestionService service(FakeModelGateway gateway, VerifyMode verifyMode) {
    TranscriptNormalizer normalizer = new TranscriptNormalizer();
    return new IngestionService(store, gateway, normalizer, new Chunker(normalizer),
      EffectiveConfigResolver.withoutOverrides(props(verifyMode)));
  }

  // ─── chunk-extraction ledger ────────────────────────────────────────────────────────────────
  // The transcript hooks re-post the whole conversation every turn, so re-ingesting an unchanged
  // prefix must cost no model calls. Chunker fills greedily from index 0, which is what makes every
  // closed chunk byte-identical across re-ingests.

  /** Small chunks so a handful of messages spans several of them. */
  private static PieriaProperties ledgerProps(boolean chunkLedgerEnabled) {
    return new PieriaProperties(null, null, null,
      new PieriaProperties.Model("small", "large", "embed", 1024, 4, null, null),
      new PieriaProperties.Ingestion(500, 2, 4, VerifyMode.NEVER,
        1, 0, 0, false, 3, 3, 32, 5, false, 5000, chunkLedgerEnabled, 0.70),
      null,
      null);
  }

  private IngestionService ledgerService(FakeModelGateway gateway, boolean chunkLedgerEnabled) {
    TranscriptNormalizer normalizer = new TranscriptNormalizer();
    return new IngestionService(store, gateway, normalizer, new Chunker(normalizer),
      EffectiveConfigResolver.withoutOverrides(ledgerProps(chunkLedgerEnabled)));
  }

  /** Records which chunks actually reached the extraction model. */
  private static class CountingGateway extends FakeModelGateway {
    private final List<Integer> extracted = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public List<UnifiedCandidate> extractUnified(Chunk chunk) {
      extracted.add(chunk.index());
      return super.extractUnified(chunk);
    }

    /** Sorted: extraction fans out across virtual threads, so completion order is not stable. */
    List<Integer> extractedChunks() {
      return extracted.stream().sorted().toList();
    }

    void reset() {
      extracted.clear();
    }
  }

  /** {@code count} distinct messages, each long enough that two of them fill a 500-char chunk. */
  private static List<Message> longMessages(int count) {
    List<Message> messages = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      messages.add(msg("user", "turn " + i + " " + ("detail" + i).repeat(30)));
    }
    return messages;
  }

  @Test
  void reIngestingAnUnchangedTranscriptMakesNoModelCalls() {
    CountingGateway gateway = new CountingGateway();
    IngestionService svc = ledgerService(gateway, true);
    List<Message> messages = longMessages(6);

    List<Memory> first = svc.ingest("proj", "s1", messages);
    assertFalse(gateway.extractedChunks().isEmpty(), "the first ingest must extract something");
    gateway.reset();

    List<Memory> second = svc.ingest("proj", "s1", messages);

    assertEquals(List.of(), gateway.extractedChunks(),
      "an unchanged transcript must not reach the extraction model at all");
    assertEquals(List.of(), second, "nothing new is stored when every chunk was already processed");
    assertFalse(first.isEmpty(), "the first ingest still stores its memories");
  }

  @Test
  void appendingTurnsReExtractsOnlyTheChunksThatChanged() {
    CountingGateway gateway = new CountingGateway();
    IngestionService svc = ledgerService(gateway, true);

    svc.ingest("proj", "s1", longMessages(6));
    int firstPassChunks = gateway.extractedChunks().size();
    gateway.reset();

    svc.ingest("proj", "s1", longMessages(8));

    List<Integer> reExtracted = gateway.extractedChunks();
    assertFalse(reExtracted.isEmpty(), "the appended turns must be extracted");
    assertFalse(reExtracted.contains(0),
      "chunk 0 is byte-identical after appending, so it must come from the ledger");
    assertTrue(reExtracted.size() < firstPassChunks,
      "only the tail should be re-extracted, not the whole transcript");
  }

  @Test
  void chunksThatExtractNothingAreStillLedgered() {
    // A gateway that finds nothing worth remembering: the chunks are still fully processed, so a
    // re-ingest must not pay for them again.
    CountingGateway gateway = new CountingGateway() {
      @Override
      public List<UnifiedCandidate> extractUnified(Chunk chunk) {
        super.extractUnified(chunk);
        return List.of();
      }
    };
    IngestionService svc = ledgerService(gateway, true);
    List<Message> messages = longMessages(6);

    svc.ingest("proj", "s1", messages);
    assertFalse(gateway.extractedChunks().isEmpty());
    gateway.reset();

    svc.ingest("proj", "s1", messages);

    assertEquals(List.of(), gateway.extractedChunks(),
      "a chunk that yielded no candidates is finished and must not be re-extracted");
  }

  @Test
  void partialCaptureDefersTheTrailingChunkAndAFlushPicksItUp() {
    List<Message> messages = longMessages(6);

    // Baseline: a final capture into a fresh session extracts every chunk.
    CountingGateway baselineGateway = new CountingGateway();
    ledgerService(baselineGateway, true)
      .ingest("proj", "baseline", messages, null, ChunkLedgerMode.ENABLED, IngestProgressListener.noop());
    List<Integer> allChunks = baselineGateway.extractedChunks();
    assertTrue(allChunks.size() > 1, "need several chunks for this test to mean anything");
    int trailing = allChunks.stream().mapToInt(Integer::intValue).max().orElseThrow();

    CountingGateway gateway = new CountingGateway();
    IngestionService svc = ledgerService(gateway, true);

    svc.ingest("proj", "s1", messages, null, ChunkLedgerMode.DEFER_TRAILING, IngestProgressListener.noop());

    assertFalse(gateway.extractedChunks().contains(trailing),
      "the trailing chunk is still growing, so a per-turn capture must leave it alone");
    assertEquals(allChunks.size() - 1, gateway.extractedChunks().size(),
      "every chunk except the trailing one should be extracted");
    gateway.reset();

    // The final capture (session end / pre-compaction) flushes what was deferred.
    svc.ingest("proj", "s1", messages, null, ChunkLedgerMode.ENABLED, IngestProgressListener.noop());

    assertEquals(List.of(trailing), gateway.extractedChunks(),
      "the flush must extract exactly the chunk that was deferred, and nothing already ledgered");
  }

  @Test
  void disablingTheLedgerReExtractsEverything() {
    CountingGateway gateway = new CountingGateway();
    IngestionService svc = ledgerService(gateway, false);
    List<Message> messages = longMessages(6);

    svc.ingest("proj", "s1", messages);
    List<Integer> firstPass = gateway.extractedChunks();
    gateway.reset();

    svc.ingest("proj", "s1", messages);

    assertEquals(firstPass, gateway.extractedChunks(),
      "with the kill switch off every ingest goes through the full pipeline, as before");
  }

  @Test
  void ledgerSkippingDoesNotInflateSourceTokenAttribution() {
    // Regression guard: source-token attribution walks every chunk in order so a message shared by
    // two overlapping chunks is counted once. If the skipped chunks were dropped from that walk, a
    // pending chunk following one would re-claim tokens already attributed by the earlier ingest.
    CountingGateway gateway = new CountingGateway();
    IngestionService svc = ledgerService(gateway, true);
    List<Message> all = longMessages(8);

    svc.ingest("proj", "s1", all.subList(0, 6));
    List<Memory> second = svc.ingest("proj", "s1", all);

    assertFalse(second.isEmpty(), "the appended turns must still produce memories");
    String profileId = store.findProfile("proj").orElseThrow().id();
    List<String> ids = store.listMemories(profileId, null, "s1").stream().map(Memory::id).toList();
    long attributed = store.sumActiveSourceTokens(profileId, ids);
    long rawTokens = all.stream().mapToLong(m -> dev.alvo.pieria.tools.Tokens.estimate(m.content())).sum();

    assertTrue(attributed <= rawTokens + ids.size(),
      "attributed source tokens (" + attributed + ") must not exceed the raw transcript ("
        + rawTokens + ") beyond per-memory ceil rounding");
  }

  @Test
  void groundedCandidateSkipsModelVerify() {
    // The candidate repeats the transcript's words exactly, so the grounding filter clears it and
    // the model verifier must never be called; the memory is still stored.
    ScriptedGateway gateway = new ScriptedGateway("alpha bravo charlie delta");
    List<Memory> stored = service(gateway, VerifyMode.GROUNDED).ingest("proj", "s1",
      List.of(msg("user", "alpha bravo charlie delta")));

    assertEquals(1, stored.size());
    assertEquals(0, gateway.verifyCalls.get(), "a grounded candidate must not reach the model verifier");
  }

  @Test
  void ungroundedCandidateGoesToModelVerify() {
    // The candidate shares no words with the transcript, so it is suspect and must be model-verified
    // (the fake verifier passes it).
    ScriptedGateway gateway = new ScriptedGateway("fabricated nonsense statement entirely");
    List<Memory> stored = service(gateway, VerifyMode.GROUNDED).ingest("proj", "s1",
      List.of(msg("user", "alpha bravo charlie delta")));

    assertEquals(1, stored.size());
    assertTrue(gateway.verifyCalls.get() >= 1, "an ungrounded candidate must be model-verified");
  }

  @Test
  void correctedCandidateIsReclassified() {
    // TYPO forces a CORRECT verdict; the corrected content invalidates the extraction-time
    // classification, so classify must run again and the corrected content is stored.
    ScriptedGateway gateway = new ScriptedGateway("statement with a TYPO inside");
    List<Memory> stored = service(gateway, VerifyMode.ALWAYS).ingest("proj", "s1",
      List.of(msg("user", "alpha bravo charlie delta")));

    assertEquals(1, stored.size());
    assertTrue(stored.getFirst().content().startsWith("corrected: "),
      "the corrected content must be what gets stored");
    assertTrue(gateway.classifyCalls.get() >= 1, "a CORRECT verdict must re-classify the corrected content");
  }

  @Test
  void verifyModeNeverStoresWithoutModelVerify() {
    ScriptedGateway gateway = new ScriptedGateway("fabricated nonsense statement entirely");
    List<Memory> stored = service(gateway, VerifyMode.NEVER).ingest("proj", "s1",
      List.of(msg("user", "alpha bravo charlie delta")));

    assertEquals(1, stored.size());
    assertEquals(0, gateway.verifyCalls.get(), "verify-mode=never must skip verification entirely");
  }
}
