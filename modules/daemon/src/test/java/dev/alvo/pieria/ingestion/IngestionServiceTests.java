package dev.alvo.pieria.ingestion;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.OutboxEntry;
import dev.alvo.pieria.domain.Profile;
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
import java.util.ArrayList;
import java.util.List;

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
  private SqliteMemoryStore store;
  private IngestionService service;

  private static PieriaProperties props() {
    return new PieriaProperties(null, null, null,
      new PieriaProperties.Model("small", "large", "embed", 1024),
      new PieriaProperties.Ingestion(10000, 2, 4, 9, 32, 5, false, 5000),
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

    store = new SqliteMemoryStore(JdbcClient.create(dataSource));
    PieriaProperties properties = props();
    TranscriptNormalizer normalizer = new TranscriptNormalizer();
    Chunker chunker = new Chunker(normalizer, properties);
    service = new IngestionService(store, new FakeModelGateway(), normalizer, chunker, properties);
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
  void longConversationTriggersDetailPassWithoutError() {
    List<Message> many = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      many.add(msg(i % 2 == 0 ? "user" : "assistant", "message number " + i));
    }
    List<Memory> stored = service.ingest("proj", "s1", many);
    assertFalse(stored.isEmpty(), "the >=9 message detail pass should still produce memories");
  }
}
