package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.OutboxEntry;
import dev.alvo.pieria.domain.Profile;
import dev.alvo.pieria.domain.RecallCandidate;
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
}
