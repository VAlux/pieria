package dev.alvo.pieria.ingestion;

import dev.alvo.pieria.config.VerifyMode;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.model.ModelUnavailableException;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link VectorizationWorker#drainOnce()} against a real {@link SqliteMemoryStore}: batch
 * draining + embedding persistence on success, one batch model call per drain with a per-entry
 * fallback when it fails, attempt increments + poison-row abandonment on repeated failure (driven by
 * {@link FakeModelGateway#setUnavailable(boolean)}).
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
      new PieriaProperties.Model("small", "large", "embed", 1024, 4, null, null),
      new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS,
        1, 0, 0, false, 3, 3, batchSize, maxAttempts, false, 5000, true, 0.70),
      null,
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

  private String memoryIdOf(String content) {
    return jdbc.sql("SELECT id FROM memories WHERE content = ?").param(content)
      .query(String.class).single();
  }

  /**
   * Records how the worker reaches the model — one batch call, or the per-text fallback — and can
   * fail the batch outright while letting individual texts succeed or fail on their own.
   */
  private static final class RecordingGateway extends FakeModelGateway {
    private final List<List<String>> batches = new ArrayList<>();
    private final List<String> singles = new ArrayList<>();
    private Set<String> poison = Set.of();
    private boolean batchFails;

    @Override
    public List<float[]> embedAll(List<String> texts) {
      batches.add(List.copyOf(texts));
      if (batchFails) {
        throw new ModelUnavailableException("batch embedding failed");
      }
      return texts.stream().map(text -> super.embed(text)).toList();
    }

    @Override
    public float[] embed(String text) {
      singles.add(text);
      if (poison.contains(text)) {
        throw new ModelUnavailableException("poison text");
      }
      return super.embed(text);
    }
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
  void wholeBatchIsEmbeddedInOneModelCall() {
    enqueueFact("alpha");
    enqueueFact("beta");
    enqueueFact("gamma");
    RecordingGateway recording = new RecordingGateway();

    assertEquals(3, new VectorizationWorker(store, recording, props(32, 5)).drainOnce());

    assertEquals(1, recording.batches.size(), "one batch call for the whole drain");
    assertEquals(3, recording.batches.getFirst().size());
    assertTrue(recording.singles.isEmpty(), "no per-text embed calls on the happy path");
  }

  @Test
  void eachMemoryStoresTheVectorForItsOwnText() {
    enqueueFact("alpha");
    enqueueFact("beta");
    enqueueFact("gamma");

    new VectorizationWorker(store, gateway, props(32, 5)).drainOnce();

    // FakeModelGateway derives the vector from the text, so a shifted batch result lands the wrong
    // vector on a memory while every count above still looks correct.
    for (String content : new String[] {"alpha", "beta", "gamma"}) {
      String id = memoryIdOf(content);
      float[] stored = store.embeddingsFor(profileId, List.of(id)).get(id);
      assertArrayEquals(gateway.embed("queries " + content), stored, "vector for " + content);
    }
  }

  @Test
  void batchFailureFallsBackToEmbeddingEachEntryOnItsOwn() {
    enqueueFact("alpha");
    enqueueFact("beta");
    enqueueFact("gamma");
    RecordingGateway recording = new RecordingGateway();
    recording.batchFails = true;
    recording.poison = Set.of("queries beta");

    // Without the fallback the whole batch fails together, and repeated drains would march the two
    // healthy entries to maxAttempts and abandon them alongside the poison one.
    assertEquals(2, new VectorizationWorker(store, recording, props(32, 5)).drainOnce());

    assertEquals(1, recording.batches.size(), "the batch is attempted first");
    assertEquals(3, recording.singles.size(), "every entry retried individually");
    assertEquals(2, embeddingCount());
    assertEquals(1, outboxSize(), "only the poison entry stays queued");
    assertEquals(memoryIdOf("beta"),
      jdbc.sql("SELECT memory_id FROM vectorization_outbox").query(String.class).single());
    assertEquals(1, jdbc.sql("SELECT attempts FROM vectorization_outbox").query(Integer.class).single());
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
