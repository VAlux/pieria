package dev.alvo.pieria.ingestion;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link VectorizationWorker#drainOnce()} against a real {@link SqliteMemoryStore}: batch
 * draining + embedding persistence on success, attempt increments + poison-row abandonment on
 * repeated failure (driven by {@link FakeModelGateway#setUnavailable(boolean)}).
 */
class VectorizationWorkerTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;
  private SqliteMemoryStore store;
  private FakeModelGateway gateway;
  private String profileId;

  private static PieriaProperties props(int batchSize, int maxAttempts) {
    return new PieriaProperties(null, null, null,
      new PieriaProperties.Model("small", "large", "embed", 1024),
      new PieriaProperties.Ingestion(10000, 2, 4, 9, batchSize, maxAttempts, false, 5000),
      null);
  }

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-worker-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(dataSource).load().migrate();

    jdbc = JdbcClient.create(dataSource);
    store = new SqliteMemoryStore(jdbc);
    gateway = new FakeModelGateway();
    profileId = store.getOrCreateProfile("proj").id();
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

  private void enqueueFact(String content) {
    store.store(profileId, new Memory(null, "s1", MemoryType.FACT, content,
      "topic." + content, null, false, "{}", "queries " + content, null));
  }

  private int outboxSize() {
    return store.drainOutbox(1000).size();
  }

  private long embeddingCount() {
    return jdbc.sql("SELECT COUNT(*) FROM memories WHERE embedding IS NOT NULL")
      .query(Long.class).single();
  }

  @Test
  void drainsBatchAndPersistsEmbeddings() {
    enqueueFact("alpha");
    enqueueFact("beta");
    enqueueFact("gamma");
    assertEquals(3, outboxSize());

    VectorizationWorker worker = new VectorizationWorker(store, gateway, props(32, 5));
    int processed = worker.drainOnce();

    assertEquals(3, processed);
    assertEquals(0, outboxSize(), "outbox rows are deleted after the embedding commits");
    assertEquals(3, embeddingCount());
  }

  @Test
  void respectsBatchSize() {
    enqueueFact("alpha");
    enqueueFact("beta");

    VectorizationWorker worker = new VectorizationWorker(store, gateway, props(1, 5));
    assertEquals(1, worker.drainOnce());
    assertEquals(1, outboxSize());
    assertEquals(1, worker.drainOnce());
    assertEquals(0, outboxSize());
  }

  @Test
  void failureIncrementsAttemptsThenAbandonsPoisonRow() {
    enqueueFact("alpha");
    gateway.setUnavailable(true);
    VectorizationWorker worker = new VectorizationWorker(store, gateway, props(32, 2));

    // attempt 0 -> fail, attempts=1, row remains
    assertEquals(0, worker.drainOnce());
    assertEquals(1, jdbc.sql("SELECT attempts FROM vectorization_outbox").query(Integer.class).single());

    // attempt 1 -> fail, attempts=2, row remains
    assertEquals(0, worker.drainOnce());
    assertEquals(2, jdbc.sql("SELECT attempts FROM vectorization_outbox").query(Integer.class).single());

    // attempts >= maxAttempts(2) -> poison row dropped, nothing embedded
    assertEquals(0, worker.drainOnce());
    assertEquals(0, outboxSize());
    assertEquals(0, embeddingCount());
  }
}
