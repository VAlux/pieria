package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.domain.ContentId;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.ingestion.model.OutboxEntry;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.domain.profile.ProfileCount;
import dev.alvo.pieria.domain.profile.ProfileStats;
import dev.alvo.pieria.domain.profile.ProfileUsage;
import dev.alvo.pieria.model.usage.InferenceTier;
import dev.alvo.pieria.model.usage.TierUsage;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.storage.MemoryStore.StoreOutcome;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, network-free integration test for {@link SqliteMemoryStore}. Builds a real SQLite
 * datasource against a temp file, runs the real V1 Flyway migration, and exercises the store
 * directly without booting the Spring context.
 */
class SqliteMemoryStoreTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;
  private SqliteMemoryStore store;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-test-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");

    Flyway.configure().dataSource(dataSource).load().migrate();

    jdbc = JdbcClient.create(dataSource);
    store = new SqliteMemoryStore(jdbc);
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
  void walModeIsActive() {
    String mode = jdbc.sql("PRAGMA journal_mode")
      .query(String.class)
      .single();
    assertEquals("wal", mode.toLowerCase());
  }

  @Test
  void flywayCreatedTables() {
    List<String> tables = jdbc.sql(
        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
      .query(String.class)
      .list();
    assertTrue(tables.contains("profiles"));
    assertTrue(tables.contains("messages"));
    assertTrue(tables.contains("memories"));
    assertTrue(tables.contains("vectorization_outbox"));
    assertTrue(tables.contains("memories_fts"));
    assertTrue(tables.contains("messages_fts"));
  }

  // ---- FTS5 migration + triggers + active filtering ----

  @Test
  void existingMemoriesAreSearchableAfterMigration() {
    // Rows inserted before any explicit FTS work must still be found (the migration rebuilds the
    // index, and triggers keep new inserts in sync).
    Profile p = store.getOrCreateProfile("fts-alpha");
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "User prefers the pnpm package manager", "s1", null, null));
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "User lives in Berlin", "s1", null, null));

    List<Memory> hits = store.searchMemoriesFts(p.id(), "package manager", 10);
    assertEquals(1, hits.size());
    assertTrue(hits.get(0).content().contains("pnpm"));
  }

  @Test
  void ftsUsesPorterStemming() {
    Profile p = store.getOrCreateProfile("fts-stem");
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "The user manages several running services", "s1", null, null));

    // 'run' stems to match 'running'; 'manage' matches 'manages'.
    assertEquals(1, store.searchMemoriesFts(p.id(), "run", 10).size());
    assertEquals(1, store.searchMemoriesFts(p.id(), "manage", 10).size());
  }

  @Test
  void ftsExcludesSupersededMemories() {
    Profile p = store.getOrCreateProfile("fts-supersede");
    store.store(p.id(), Memory.of(MemoryType.FACT, "User lives in Berlin", "s1", "location", null));
    store.store(p.id(), Memory.of(MemoryType.FACT, "User lives in Munich", "s2", "location", null));

    List<Memory> hits = store.searchMemoriesFts(p.id(), "lives", 10);
    assertEquals(1, hits.size());
    assertEquals("User lives in Munich", hits.get(0).content());
  }

  @Test
  void ftsDropsForgottenMemoriesViaTrigger() {
    Profile p = store.getOrCreateProfile("fts-forget");
    Memory stored = store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "Temporary preference for vim", "s1", null, null));
    assertEquals(1, store.searchMemoriesFts(p.id(), "vim", 10).size());

    store.forgetMemory(p.id(), stored.id());
    // Still indexed (row not deleted), but the active-filtered query excludes it.
    assertTrue(store.searchMemoriesFts(p.id(), "vim", 10).isEmpty());
  }

  @Test
  void ftsHandlesRawQueryWithSpecialCharacters() {
    Profile p = store.getOrCreateProfile("fts-raw");
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "Deploy uses the staging environment", "s1", null, null));

    // A raw query with FTS operators/punctuation must not throw.
    List<Memory> hits = store.searchMemoriesFts(p.id(), "what about \"staging\" (env)? AND OR *", 10);
    assertEquals(1, hits.size());
  }

  @Test
  void messageFtsSurfacesMemoriesFromMatchingSession() {
    Profile p = store.getOrCreateProfile("msg-fts");
    store.insertMessages(p.id(), "s1", List.of(
      Message.of("s1", "user", "Please configure the kubernetes ingress controller")));
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "Deployment target decided", "s1", null, null));
    // An unrelated session/memory must not be surfaced by the s1 message hit.
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "Unrelated note", "s2", null, null));

    List<Memory> hits = store.searchMemoriesByMessageFts(p.id(), "kubernetes ingress", 10);
    assertEquals(1, hits.size());
    assertEquals("Deployment target decided", hits.get(0).content());
  }

  @Test
  void exactKeyLookupOrdersByInputPriority() {
    Profile p = store.getOrCreateProfile("keys");
    store.store(p.id(), Memory.of(MemoryType.FACT, "pkg manager is pnpm", "s1", "tooling.pkg", null));
    store.store(p.id(), Memory.of(MemoryType.INSTRUCTION, "run tests with gradle", "s1", "tooling.test", null));
    store.store(p.id(), Memory.of(MemoryType.TASK, "do a thing", "s1", "tooling.pkg", null)); // tasks excluded

    List<Memory> hits = store.exactKeyLookup(p.id(), List.of("tooling.test", "tooling.pkg"), 10);
    assertEquals(2, hits.size());
    assertEquals("tooling.test", hits.get(0).topicKey());
    assertEquals("tooling.pkg", hits.get(1).topicKey());
  }

  @Test
  void vectorSearchUnavailableWithoutExtensionConstructor() {
    // The single-arg constructor reports vector search unavailable, so the vector
    // channel is a graceful no-op even on a machine that has sqlite-vec.
    assertFalse(store.isVectorSearchAvailable());
    assertTrue(store.vectorSearch("any", new float[] {1f, 2f, 3f}, 5).isEmpty());
    assertEquals(0, store.backfillVectors());
  }

  @Test
  void getOrCreateProfileIsIdempotent() {
    Profile first = store.getOrCreateProfile("alice");
    Profile second = store.getOrCreateProfile("alice");
    assertNotNull(first.id());
    assertEquals(first.id(), second.id());
    assertEquals("alice", first.name());
  }

  @Test
  void findProfileReturnsEmptyWhenAbsent() {
    assertTrue(store.findProfile("ghost").isEmpty());
    store.getOrCreateProfile("present");
    assertTrue(store.findProfile("present").isPresent());
  }

  @Test
  void listProfilesReturnsActiveCountsOrderedByName() {
    Profile zed = store.getOrCreateProfile("zed");
    Profile amy = store.getOrCreateProfile("amy");
    store.getOrCreateProfile("empty");

    store.insertMemory(amy.id(), Memory.of(MemoryType.FACT, "a fact", "s1", null, null));
    store.insertMemory(amy.id(), Memory.of(MemoryType.EVENT, "an event", "s1", null, null));
    // Superseded memories must not be counted.
    store.store(zed.id(), Memory.of(MemoryType.FACT, "old", "s1", "k", null));
    store.store(zed.id(), Memory.of(MemoryType.FACT, "new", "s2", "k", null));

    List<ProfileCount> profiles = store.listProfiles();

    assertEquals(List.of("amy", "empty", "zed"),
      profiles.stream().map(p -> p.profile().name()).toList());
    assertEquals(2, profiles.get(0).activeCount()); // amy
    assertEquals(0, profiles.get(1).activeCount()); // empty
    assertEquals(1, profiles.get(2).activeCount()); // zed (one superseded, one active)
  }

  @Test
  void profileStatsAggregatesByTypeSessionsAndRange() {
    Profile p = store.getOrCreateProfile("stats");
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "f1", "s1", null, null));
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "f2", "s2", null, null));
    store.insertMemory(p.id(), Memory.of(MemoryType.INSTRUCTION, "i1", "s1", null, null));
    // A supersession: one active 'new', one superseded 'old'.
    store.store(p.id(), Memory.of(MemoryType.FACT, "old", "s3", "key", null));
    store.store(p.id(), Memory.of(MemoryType.FACT, "new", "s3", "key", null));

    ProfileStats stats = store.profileStats(p.id());

    assertEquals(4, stats.totalActive()); // f1, f2, i1, new
    assertEquals(3L, stats.byType().get("fact"));
    assertEquals(1L, stats.byType().get("instruction"));
    assertEquals(0L, stats.byType().get("event"));
    assertEquals(0L, stats.byType().get("task"));
    assertEquals(1, stats.superseded());
    assertEquals(3, stats.sessions()); // s1, s2, s3
    assertNotNull(stats.firstMemoryAt());
    assertNotNull(stats.lastMemoryAt());
  }

  @Test
  void profileStatsOnEmptyProfileHasNullRange() {
    Profile p = store.getOrCreateProfile("blank");
    ProfileStats stats = store.profileStats(p.id());

    assertEquals(0, stats.totalActive());
    assertEquals(0, stats.sessions());
    assertNull(stats.firstMemoryAt());
    assertNull(stats.lastMemoryAt());
  }

  @Test
  void usageStatsIsEmptyWhenNoRow() {
    Profile p = store.getOrCreateProfile("no-usage");
    assertEquals(ProfileUsage.empty(), store.usageStats(p.id()));
  }

  @Test
  void recordRecallUsageAccumulatesSingleSavingsFigure() {
    Profile p = store.getOrCreateProfile("usage");

    // source 12, answer 2 -> saved 10.
    store.recordRecallUsage(p.id(), 12, 2);
    ProfileUsage u = store.usageStats(p.id());
    assertEquals(1, u.recallCount());
    assertEquals(10, u.tokensSaved());
    assertEquals(2, u.tokensRecallServed());

    // Second recall accumulates: saved 4 more.
    store.recordRecallUsage(p.id(), 5, 1);
    u = store.usageStats(p.id());
    assertEquals(2, u.recallCount());
    assertEquals(14, u.tokensSaved());

    // An answer larger than its source floors the saving at zero rather than going negative.
    store.recordRecallUsage(p.id(), 1, 100);
    u = store.usageStats(p.id());
    assertEquals(3, u.recallCount());
    assertEquals(14, u.tokensSaved());
  }

  @Test
  void recordIngestUsageAccumulatesCompressionTotals() {
    Profile p = store.getOrCreateProfile("ingest-usage");

    store.recordIngestUsage(p.id(), 612, 88);
    store.recordIngestUsage(p.id(), 100, 20);
    ProfileUsage u = store.usageStats(p.id());

    assertEquals(2, u.ingestCount());
    assertEquals(712, u.tokensIngested());
    assertEquals(108, u.tokensStored());
  }

  @Test
  void inferenceUsageIsEmptyWhenNoRows() {
    Profile p = store.getOrCreateProfile("no-spend");
    assertTrue(store.inferenceUsage(p.id()).isEmpty());
  }

  @Test
  void recordInferenceUsageUpsertsAndAccumulatesPerTier() {
    Profile p = store.getOrCreateProfile("spend");

    store.recordInferenceUsage(p.id(), java.util.Map.of(
      InferenceTier.EXTRACTION, new TierUsage(3, 1_200, 80),
      InferenceTier.SYNTHESIS, new TierUsage(1, 400, 120)));
    // Second operation accumulates onto the existing rows; embedding appears for the first time.
    store.recordInferenceUsage(p.id(), java.util.Map.of(
      InferenceTier.EXTRACTION, new TierUsage(2, 800, 20),
      InferenceTier.EMBEDDING, new TierUsage(5, 0, 0)));

    java.util.Map<InferenceTier, TierUsage> usage = store.inferenceUsage(p.id());
    assertEquals(3, usage.size());

    TierUsage extraction = usage.get(InferenceTier.EXTRACTION);
    assertEquals(5, extraction.calls());
    assertEquals(2_000, extraction.promptTokens());
    assertEquals(100, extraction.completionTokens());

    TierUsage synthesis = usage.get(InferenceTier.SYNTHESIS);
    assertEquals(1, synthesis.calls());
    assertEquals(400, synthesis.promptTokens());
    assertEquals(120, synthesis.completionTokens());

    TierUsage embedding = usage.get(InferenceTier.EMBEDDING);
    assertEquals(5, embedding.calls());
    assertEquals(0, embedding.promptTokens());

    // An empty snapshot is a no-op (and never throws).
    store.recordInferenceUsage(p.id(), java.util.Map.of());
    assertEquals(5, store.inferenceUsage(p.id()).get(InferenceTier.EXTRACTION).calls());
  }

  @Test
  void insertMessagesIsIdempotent() {
    Profile p = store.getOrCreateProfile("bob");
    List<Message> messages = List.of(
      Message.of("s1", "user", "hello world"),
      Message.of("s1", "assistant", "hi there"));

    store.insertMessages(p.id(), "s1", messages);
    store.insertMessages(p.id(), "s1", messages);

    int count = jdbc.sql("SELECT COUNT(*) FROM messages WHERE profile_id = ?")
      .param(p.id())
      .query(Integer.class)
      .single();
    assertEquals(2, count);
  }

  @Test
  void identicalMessagesAndMemoriesCoexistAcrossProfiles() {
    Profile first = store.getOrCreateProfile("cross-profile-a");
    Profile second = store.getOrCreateProfile("cross-profile-b");
    Message message = Message.of("shared-session", "user", "same transcript content");

    store.insertMessages(first.id(), "shared-session", List.of(message));
    store.insertMessages(second.id(), "shared-session", List.of(message));

    String firstMessageId = messageId(first.id());
    String secondMessageId = messageId(second.id());
    assertNotEquals(firstMessageId, secondMessageId);
    assertEquals(ContentId.forMessage(first.id(), "shared-session", "user", "same transcript content"),
      firstMessageId);
    assertEquals(ContentId.forMessage(second.id(), "shared-session", "user", "same transcript content"),
      secondMessageId);

    Memory memory = Memory.of(MemoryType.FACT, "same derived fact", "shared-session", null, null);
    StoreOutcome firstStore = store.store(first.id(), memory);
    StoreOutcome secondStore = store.store(second.id(), memory);

    assertTrue(firstStore.inserted());
    assertTrue(secondStore.inserted());
    assertNotEquals(firstStore.stored().id(), secondStore.stored().id());
    assertEquals(1, store.listMemories(first.id(), null, null).size());
    assertEquals(1, store.listMemories(second.id(), null, null).size());
  }

  @Test
  void legacyUnscopedIdsRemainIdempotentForOwnerButDoNotBlockAnotherProfile() {
    Profile owner = store.getOrCreateProfile("legacy-owner");
    Profile newcomer = store.getOrCreateProfile("legacy-newcomer");
    String legacyMessageId = ContentId.forMessage("legacy-session", "user", "legacy shared message");
    jdbc.sql("""
        INSERT INTO messages (id, profile_id, session_id, role, content, created_at)
        VALUES (?, ?, ?, ?, ?, ?)""")
      .params(legacyMessageId, owner.id(), "legacy-session", "user", "legacy shared message",
        java.time.Instant.now().toString())
      .update();

    Message message = Message.of("legacy-session", "user", "legacy shared message");
    store.insertMessages(owner.id(), "legacy-session", List.of(message));
    store.insertMessages(newcomer.id(), "legacy-session", List.of(message));

    assertEquals(legacyMessageId, messageId(owner.id()));
    assertEquals(ContentId.forMessage(
      newcomer.id(), "legacy-session", "user", "legacy shared message"), messageId(newcomer.id()));

    Memory memory = Memory.of(MemoryType.FACT, "legacy shared fact", "legacy-session", null, null);
    String legacyMemoryId = ContentId.forMemory("legacy-session", MemoryType.FACT, "legacy shared fact");
    jdbc.sql("""
        INSERT INTO memories
          (id, profile_id, session_id, type, content, superseded, payload, created_at)
        VALUES (?, ?, ?, ?, ?, 0, '{}', ?)""")
      .params(legacyMemoryId, owner.id(), "legacy-session", "fact", "legacy shared fact",
        java.time.Instant.now().toString())
      .update();

    StoreOutcome ownerStore = store.store(owner.id(), memory);
    StoreOutcome newcomerStore = store.store(newcomer.id(), memory);

    assertFalse(ownerStore.inserted());
    assertEquals(legacyMemoryId, ownerStore.stored().id());
    assertTrue(newcomerStore.inserted());
    assertEquals(ContentId.forMemory(
      newcomer.id(), "legacy-session", MemoryType.FACT, "legacy shared fact"),
      newcomerStore.stored().id());
    assertNotEquals(ownerStore.stored().id(), newcomerStore.stored().id());
  }

  private String messageId(String profileId) {
    return jdbc.sql("SELECT id FROM messages WHERE profile_id = ?")
      .param(profileId)
      .query(String.class)
      .single();
  }

  @Test
  void insertMemoryThenListReturnsIt() {
    Profile p = store.getOrCreateProfile("carol");
    Memory mem = Memory.of(MemoryType.FACT, "User prefers dark mode", "s1", null, null);

    Memory stored = store.insertMemory(p.id(), mem);
    assertNotNull(stored.id());
    assertNotNull(stored.createdAt());
    assertEquals("{}", stored.payload());

    List<Memory> listed = store.listMemories(p.id(), null, null);
    assertEquals(1, listed.size());
    assertEquals("User prefers dark mode", listed.get(0).content());
    assertEquals(MemoryType.FACT, listed.get(0).type());
  }

  @Test
  void typeAndSessionFiltersWork() {
    Profile p = store.getOrCreateProfile("dave");
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "fact a", "s1", null, null));
    store.insertMemory(p.id(), Memory.of(MemoryType.TASK, "task b", "s1", null, null));
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "fact c", "s2", null, null));

    assertEquals(2, store.listMemories(p.id(), MemoryType.FACT, null).size());
    assertEquals(1, store.listMemories(p.id(), MemoryType.TASK, null).size());
    assertEquals(2, store.listMemories(p.id(), null, "s1").size());
    assertEquals(1, store.listMemories(p.id(), MemoryType.FACT, "s1").size());
  }

  @Test
  void forgetMemoryMarksSupersededAndIsReportedOnce() {
    Profile p = store.getOrCreateProfile("erin");
    Memory stored = store.insertMemory(
      p.id(), Memory.of(MemoryType.FACT, "ephemeral", "s1", null, null));

    assertTrue(store.forgetMemory(p.id(), stored.id()));
    assertFalse(store.forgetMemory(p.id(), stored.id()));

    assertTrue(store.listMemories(p.id(), null, null).isEmpty());
  }

  @Test
  void exportProfileIncludesSupersededMemories() {
    Profile p = store.getOrCreateProfile("frank");
    Memory kept = store.insertMemory(
      p.id(), Memory.of(MemoryType.FACT, "kept", "s1", null, null));
    Memory gone = store.insertMemory(
      p.id(), Memory.of(MemoryType.FACT, "forgotten", "s1", null, null));
    store.forgetMemory(p.id(), gone.id());

    List<ExportRow> rows = store.exportProfile(p.id());
    assertEquals(2, rows.size());
    assertTrue(rows.stream().allMatch(r -> r.profileName().equals("frank")));
    assertTrue(rows.stream().anyMatch(r -> r.memory().superseded()));
    assertTrue(rows.stream().anyMatch(r -> r.memory().id().equals(kept.id())));
  }

  @Test
  void findRecallCandidatesReturnsMatchingMemory() {
    Profile p = store.getOrCreateProfile("grace");
    store.insertMemory(p.id(),
      Memory.of(MemoryType.FACT, "User lives in Berlin", "s1", null, null));
    store.insertMemory(p.id(),
      Memory.of(MemoryType.FACT, "User likes coffee", "s1", null, null));

    List<RecallCandidate> hits = store.findRecallCandidates(p.id(), "berlin", 10);
    assertEquals(1, hits.size());
    assertEquals("User lives in Berlin", hits.get(0).memory().content());
    assertTrue(hits.get(0).score() > 0);
    assertEquals("fts-memory", hits.get(0).source());
  }

  // ---- Storage helpers ----

  private int outboxCount() {
    return jdbc.sql("SELECT COUNT(*) FROM vectorization_outbox").query(Integer.class).single();
  }

  private boolean hasOutbox(String memoryId) {
    return jdbc.sql("SELECT COUNT(*) FROM vectorization_outbox WHERE memory_id = ?")
      .param(memoryId).query(Integer.class).single() > 0;
  }

  private boolean isSuperseded(String memoryId) {
    return jdbc.sql("SELECT superseded FROM memories WHERE id = ?")
      .param(memoryId).query(Integer.class).single() != 0;
  }

  private byte[] embeddingOf(String memoryId) {
    return jdbc.sql("SELECT embedding FROM memories WHERE id = ?")
      .param(memoryId).query(byte[].class).optional().orElse(null);
  }

  @Test
  void storeFactEnqueuesVectorAndReturnsOutcome() {
    Profile p = store.getOrCreateProfile("hank");
    StoreOutcome outcome = store.store(p.id(),
      Memory.of(MemoryType.FACT, "User likes tea", "s1", "drink.pref", null));

    assertNotNull(outcome.stored().id());
    assertNull(outcome.supersededId());
    assertTrue(outcome.enqueuedVector());
    assertTrue(outcome.inserted());
    assertTrue(hasOutbox(outcome.stored().id()));
  }

  @Test
  void storeKeyedFactSupersedesPredecessorAndClearsItsEmbedding() {
    Profile p = store.getOrCreateProfile("iris");
    StoreOutcome first = store.store(p.id(),
      Memory.of(MemoryType.FACT, "User lives in Berlin", "s1", "location", null));
    // Give the old row an embedding so we can prove it is cleared on supersession.
    store.completeVectorization(first.stored().id(), new float[] {1f, 2f, 3f});
    assertNotNull(embeddingOf(first.stored().id()));

    StoreOutcome second = store.store(p.id(),
      Memory.of(MemoryType.FACT, "User lives in Munich", "s2", "location", null));

    assertEquals(first.stored().id(), second.supersededId());
    assertEquals(first.stored().id(), second.stored().supersedes());
    assertTrue(isSuperseded(first.stored().id()));
    assertNull(embeddingOf(first.stored().id()));
    assertFalse(isSuperseded(second.stored().id()));

    // Only the new fact is active.
    List<Memory> active = store.listMemories(p.id(), MemoryType.FACT, null);
    assertEquals(1, active.size());
    assertEquals("User lives in Munich", active.get(0).content());
  }

  // The drift this exists for, taken from real profile data: the same fact re-extracted next
  // session under a different invented topic key, which exact-key supersession cannot see.
  @Test
  void storeSupersedesANearDuplicateWhoseTopicKeyDrifted() {
    Profile p = store.getOrCreateProfile("dup-drift");
    StoreOutcome first = store.store(p.id(), Memory.of(MemoryType.FACT,
      "The Pieria daemon MCP is the primary long-term knowledge base for durable facts, "
        + "preferences, project context, recurring workflows, decisions, environment details, and "
        + "other information likely to help in future sessions.",
      "s1", "pieria.mcp", null));
    store.completeVectorization(first.stored().id(), new float[] {1f, 2f, 3f});

    StoreOutcome second = store.store(p.id(), Memory.of(MemoryType.FACT,
      "The Pieria daemon MCP is treated as the primary long-term knowledge base for durable facts, "
        + "preferences, project context, recurring workflows, decisions, environment details, and "
        + "other information likely to help in future sessions.",
      "s2", "pieria.mcp.role", null));

    assertEquals(first.stored().id(), second.supersededId());
    assertEquals(first.stored().id(), second.stored().supersedes());
    assertTrue(isSuperseded(first.stored().id()));
    assertNull(embeddingOf(first.stored().id()));
    assertEquals(1, store.listMemories(p.id(), MemoryType.FACT, null).size());
  }

  @Test
  void storeKeepsDistinctFactsThatMerelyShareVocabulary() {
    Profile p = store.getOrCreateProfile("dup-distinct");
    store.store(p.id(), Memory.of(MemoryType.FACT,
      "The daemon binds a REST API on 127.0.0.1 in local mode.", "s1", "daemon.binding", null));
    StoreOutcome second = store.store(p.id(), Memory.of(MemoryType.FACT,
      "The gateway forwards MCP tool calls to the daemon over HTTP.", "s1", "gateway.role", null));

    assertNull(second.supersededId());
    assertEquals(2, store.listMemories(p.id(), MemoryType.FACT, null).size());
  }

  // Different files, near-identical templated summaries: the one population where content
  // similarity is not identity, and the one whose topic key never drifts.
  @Test
  void storeNeverMergesCodeDerivedMemoriesOfDifferentFiles() {
    Profile p = store.getOrCreateProfile("dup-code");
    store.store(p.id(), Memory.of(MemoryType.FACT,
      "Source file onboarding/TextDiscovery.java (java) defines: class Doc, class Discovery, "
        + "method scan, method parse, method emit.",
      "code", "code:file:onboarding/TextDiscovery.java", null));
    StoreOutcome second = store.store(p.id(), Memory.of(MemoryType.FACT,
      "Source file onboarding/PdfDiscovery.java (java) defines: class Doc, class Discovery, "
        + "method scan, method parse, method emit.",
      "code", "code:file:onboarding/PdfDiscovery.java", null));

    assertNull(second.supersededId());
    assertEquals(2, store.listMemories(p.id(), MemoryType.FACT, null).size());
  }

  @Test
  void nearDuplicateSupersessionIsScopedToOneProfileAndType() {
    String content = "The daemon owns the embedded SQLite database and is its only writer process.";
    Profile a = store.getOrCreateProfile("dup-scope-a");
    Profile b = store.getOrCreateProfile("dup-scope-b");
    store.store(a.id(), Memory.of(MemoryType.FACT, content, "s1", "db.owner", null));

    StoreOutcome otherProfile = store.store(b.id(), Memory.of(MemoryType.FACT, content, "s1", "db.owner2", null));
    StoreOutcome otherType =
      store.store(a.id(), Memory.of(MemoryType.INSTRUCTION, content, "s1", "db.owner3", null));

    assertNull(otherProfile.supersededId());
    assertNull(otherType.supersededId());
  }

  // Re-ingesting the same content in the same session must stay idempotent, not supersede itself.
  @Test
  void reIngestingIdenticalContentDoesNotSupersedeItself() {
    Profile p = store.getOrCreateProfile("dup-idempotent");
    String content = "Vectorization runs asynchronously through a transactional outbox worker.";
    StoreOutcome first = store.store(p.id(), Memory.of(MemoryType.FACT, content, "s1", "vec.async", null));
    StoreOutcome again = store.store(p.id(), Memory.of(MemoryType.FACT, content, "s1", "vec.async", null));

    assertEquals(first.stored().id(), again.stored().id());
    assertNull(again.supersededId());
    assertFalse(isSuperseded(first.stored().id()));
    assertEquals(1, store.listMemories(p.id(), MemoryType.FACT, null).size());
  }

  @Test
  void eventsAndTasksAreAppendOnly() {
    Profile p = store.getOrCreateProfile("jane");
    StoreOutcome e1 = store.store(p.id(),
      Memory.of(MemoryType.EVENT, "logged in", "s1", "session.event", null));
    StoreOutcome e2 = store.store(p.id(),
      Memory.of(MemoryType.EVENT, "logged out", "s1", "session.event", null));
    assertNull(e2.supersededId());
    assertFalse(isSuperseded(e1.stored().id()));

    // Tasks are never embedded.
    StoreOutcome t1 = store.store(p.id(),
      Memory.of(MemoryType.TASK, "buy milk", "s1", "todo", null));
    assertFalse(t1.enqueuedVector());
    assertFalse(hasOutbox(t1.stored().id()));
  }

  @Test
  void reStoringSameMemoryDoesNotDuplicateOutbox() {
    Profile p = store.getOrCreateProfile("kyle");
    Memory mem = Memory.of(MemoryType.FACT, "stable fact", "s1", null, null);
    StoreOutcome first = store.store(p.id(), mem);
    StoreOutcome second = store.store(p.id(), mem);

    assertEquals(first.stored().id(), second.stored().id());
    assertTrue(first.inserted());
    assertFalse(second.inserted());
    assertTrue(first.enqueuedVector());
    assertFalse(second.enqueuedVector());
    assertEquals(1, outboxCount());
  }

  @Test
  void drainOutboxIsOldestFirstAndRespectsLimit() {
    Profile p = store.getOrCreateProfile("lena");
    StoreOutcome a = store.store(p.id(), Memory.of(MemoryType.FACT, "a", "s1", "ka", null));
    StoreOutcome b = store.store(p.id(), Memory.of(MemoryType.FACT, "b", "s1", "kb", null));
    store.store(p.id(), Memory.of(MemoryType.FACT, "c", "s1", "kc", null));

    List<OutboxEntry> batch = store.drainOutbox(2);
    assertEquals(2, batch.size());
    assertEquals(a.stored().id(), batch.get(0).memoryId());
    assertEquals(b.stored().id(), batch.get(1).memoryId());
  }

  @Test
  void recordOutboxFailureIncrementsAttempts() {
    Profile p = store.getOrCreateProfile("mona");
    StoreOutcome s = store.store(p.id(), Memory.of(MemoryType.FACT, "flaky", "s1", null, null));

    store.recordOutboxFailure(s.stored().id(), "boom");
    store.recordOutboxFailure(s.stored().id(), "boom again");

    int attempts = jdbc.sql("SELECT attempts FROM vectorization_outbox WHERE memory_id = ?")
      .param(s.stored().id()).query(Integer.class).single();
    assertEquals(2, attempts);
  }

  @Test
  void completeVectorizationWritesEmbeddingAndRemovesOutbox() {
    Profile p = store.getOrCreateProfile("nora");
    StoreOutcome s = store.store(p.id(), Memory.of(MemoryType.FACT, "embed me", "s1", null, null));
    assertTrue(hasOutbox(s.stored().id()));

    store.completeVectorization(s.stored().id(), new float[] {0.1f, 0.2f, 0.3f, 0.4f});

    assertFalse(hasOutbox(s.stored().id()));
    byte[] blob = embeddingOf(s.stored().id());
    assertNotNull(blob);
    assertEquals(4 * Float.BYTES, blob.length);
  }

  @Test
  void findMemoryByIdLooksUpAcrossProfiles() {
    Profile p = store.getOrCreateProfile("opal");
    StoreOutcome s = store.store(p.id(), Memory.of(MemoryType.FACT, "find me", "s1", null, null));

    assertTrue(store.findMemoryById(s.stored().id()).isPresent());
    assertTrue(store.findMemoryById("nope").isEmpty());
  }

  @Test
  void findActiveByTopicKeyExcludesSupersededAndNullKey() {
    Profile p = store.getOrCreateProfile("pete");
    store.store(p.id(), Memory.of(MemoryType.FACT, "old role", "s1", "role", null));
    store.store(p.id(), Memory.of(MemoryType.FACT, "new role", "s2", "role", null));

    List<Memory> active = store.findActiveByTopicKey(p.id(), MemoryType.FACT, "role");
    assertEquals(1, active.size());
    assertEquals("new role", active.get(0).content());
    assertTrue(store.findActiveByTopicKey(p.id(), MemoryType.FACT, null).isEmpty());
  }

  @Test
  void completeVectorizationLeavesNoPartialStateWhenItFails() {
    Profile p = store.getOrCreateProfile("quinn");
    StoreOutcome s = store.store(p.id(), Memory.of(MemoryType.FACT, "tx safe", "s1", null, null));

    // A null embedding fails before any write; the outbox row and absent embedding are unchanged.
    assertThrows(Exception.class, () -> store.completeVectorization(s.stored().id(), null));

    assertTrue(hasOutbox(s.stored().id()), "outbox row must survive a failed completion");
    assertNull(embeddingOf(s.stored().id()), "no embedding must be written on failure");
  }

  // ---- findCodeMemoriesBySymbolIds ----

  @Test
  void findCodeMemoriesBySymbolIdsIgnoresRowsWithMalformedPayload() {
    // A model-extracted memory can carry a payload that is not valid JSON. Such a row must not
    // abort the whole symbol lookup: json_each() raises "malformed JSON" and takes the channel
    // down for every other memory in the profile.
    Profile p = store.getOrCreateProfile("symbol-malformed");
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "Audit task", "s1", null,
      "{\"requirements\":[\"a\",\"output_file\":\"docs/x.md\"]}"));
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "Bar.java overview", "s1", null,
      "{\"source\":\"code\",\"symbolIds\":[\"sym-bar\"]}"));

    List<Memory> hits = store.findCodeMemoriesBySymbolIds(p.id(), List.of("sym-bar"), 10);

    assertEquals(1, hits.size());
    assertEquals("Bar.java overview", hits.get(0).content());
  }

  @Test
  void findCodeMemoriesBySymbolIdsIgnoresRowsWhosePayloadIsNotAnObject() {
    // Valid JSON that is not an object (scalar/array) has no $.symbolIds and must simply not match.
    Profile p = store.getOrCreateProfile("symbol-nonobject");
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "scalar payload", "s1", null, "5"));
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "array payload", "s1", null, "[1,2]"));
    store.insertMemory(p.id(), Memory.of(MemoryType.FACT, "Bar.java overview", "s1", null,
      "{\"source\":\"code\",\"symbolIds\":[\"sym-bar\"]}"));

    List<Memory> hits = store.findCodeMemoriesBySymbolIds(p.id(), List.of("sym-bar"), 10);

    assertEquals(1, hits.size());
    assertEquals("Bar.java overview", hits.get(0).content());
  }

  // ---- createProfile / deleteProfile ----

  @Test
  void createProfileRejectsDuplicateName() {
    store.createProfile("dup");
    assertTrue(store.findProfile("dup").isPresent());
    assertThrows(dev.alvo.pieria.domain.error.ConflictException.class, () -> store.createProfile("dup"));
  }

  @Test
  void deleteProfileRemovesProfileAndAllItsMemories() {
    Profile victim = store.getOrCreateProfile("victim");
    store.store(victim.id(), Memory.of(MemoryType.FACT, "User lives in Berlin", "s1", "location", null));
    store.store(victim.id(), Memory.of(MemoryType.FACT, "User lives in Munich", "s1", "location", null)); // supersedes the first
    store.insertMessages(victim.id(), "s1",
      List.of(Message.of("s1", "user", "please remember where I live")));
    store.putProfileConfig(victim.id(), "{\"foo\":true}");

    // A second profile that must survive the delete untouched.
    Profile keep = store.getOrCreateProfile("keeper");
    store.store(keep.id(), Memory.of(MemoryType.FACT, "Keeper likes Berlin too", "s2", null, null));

    store.deleteProfile(victim.id());

    assertTrue(store.findProfile("victim").isEmpty(), "profile row must be gone");
    assertTrue(store.exportProfile(victim.id()).isEmpty(), "no memories may remain");
    assertTrue(store.getProfileConfig(victim.id()).isEmpty(), "profile config must be gone");
    // FTS delete-triggers must have fired: the victim's content is no longer searchable.
    assertTrue(store.searchMemoriesFts(victim.id(), "Munich", 10).isEmpty());
    assertEquals(0, countRows("messages", victim.id()));

    // The keeper profile and its memory are untouched.
    assertTrue(store.findProfile("keeper").isPresent());
    assertEquals(1, store.exportProfile(keep.id()).size());
    assertTrue(store.listProfiles().stream().noneMatch(pc -> pc.profile().name().equals("victim")));
  }

  private long countRows(String table, String profileId) {
    return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE profile_id = ?")
      .param(profileId)
      .query(Long.class)
      .single();
  }

  @Test
  void storePersistsProvidedSourceTokens() {
    Profile p = store.getOrCreateProfile("src-tokens");
    Memory m = Memory.of(MemoryType.FACT, "a distilled fact", "s1", null, null);

    StoreOutcome outcome = store.store(p.id(), m, GraphFragment.empty(), 500);

    long persisted = jdbc.sql("SELECT source_tokens FROM memories WHERE id = ?")
      .param(outcome.stored().id())
      .query(Long.class)
      .single();
    assertEquals(500, persisted);
  }

  @Test
  void storeWithoutSourceTokensDefaultsToContentTokens() {
    Profile p = store.getOrCreateProfile("src-default");
    // 40 chars -> ceil(40/4) = 10 tokens. This is the explicit `remember` path: source is the
    // memory itself, so a recall on it saves nothing.
    Memory m = Memory.of(MemoryType.FACT, "a".repeat(40), "s1", null, null);

    StoreOutcome outcome = store.store(p.id(), m);

    long persisted = jdbc.sql("SELECT source_tokens FROM memories WHERE id = ?")
      .param(outcome.stored().id())
      .query(Long.class)
      .single();
    assertEquals(10, persisted);
  }

  @Test
  void reStoringSameContentKeepsOriginalSourceTokens() {
    Profile p = store.getOrCreateProfile("src-idempotent");
    Memory m = Memory.of(MemoryType.FACT, "a stable fact", "s1", null, null);

    StoreOutcome first = store.store(p.id(), m, GraphFragment.empty(), 400);
    store.store(p.id(), m, GraphFragment.empty(), 999);

    long persisted = jdbc.sql("SELECT source_tokens FROM memories WHERE id = ?")
      .param(first.stored().id())
      .query(Long.class)
      .single();
    assertEquals(400, persisted);
  }

  @Test
  void sumActiveSourceTokensAddsUpOnlyTheRequestedActiveMemories() {
    Profile p = store.getOrCreateProfile("sum-src");
    StoreOutcome a = store.store(p.id(), Memory.of(MemoryType.FACT, "fact a", "s1", null, null),
      GraphFragment.empty(), 300);
    StoreOutcome b = store.store(p.id(), Memory.of(MemoryType.FACT, "fact b", "s1", null, null),
      GraphFragment.empty(), 200);
    store.store(p.id(), Memory.of(MemoryType.FACT, "fact c", "s1", null, null),
      GraphFragment.empty(), 999);

    assertEquals(500, store.sumActiveSourceTokens(p.id(), List.of(a.stored().id(), b.stored().id())));
    // Duplicate ids must not be counted twice.
    assertEquals(300, store.sumActiveSourceTokens(p.id(), List.of(a.stored().id(), a.stored().id())));
    assertEquals(0, store.sumActiveSourceTokens(p.id(), List.of()));
    assertEquals(0, store.sumActiveSourceTokens(p.id(), List.of("no-such-id")));
  }

}
