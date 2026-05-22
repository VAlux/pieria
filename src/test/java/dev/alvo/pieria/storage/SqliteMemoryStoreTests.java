package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.Profile;
import dev.alvo.pieria.domain.RecallCandidate;
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
  void findProfileReturnsNullWhenAbsent() {
    assertNull(store.findProfile("ghost"));
    store.getOrCreateProfile("present");
    assertNotNull(store.findProfile("present"));
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
}
