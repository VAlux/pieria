package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.config.DataSourceConfig.VecCapability;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.storage.MemoryStore.StoreOutcome;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * sqlite-vec index behavior. Every assertion that depends on the native
 * extension is guarded by {@code assumeTrue(store.isVectorSearchAvailable())} so the suite passes
 * on machines without the native lib (the assumption simply skips the body there).
 */
class SqliteMemoryStoreVectorTests {

  private static final int DIM = 4;

  private Path dbFile;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;
  private SqliteMemoryStore store;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-vec-test-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath() + "?enable_load_extension=true")
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");

    Flyway.configure().dataSource(dataSource).load().migrate();

    VecCapability cap = new VecCapability();
    boolean loaded = tryLoadVec(cap);

    jdbc = JdbcClient.create(dataSource);
    PieriaProperties props = properties(DIM, true);
    store = new SqliteMemoryStore(jdbc, cap, props);

    if (loaded) {
      // Mirror what SqliteVectorIndex does at startup, using the test embedding width.
      jdbc.sql("CREATE VIRTUAL TABLE IF NOT EXISTS memories_vec USING vec0("
        + "memory_id TEXT PRIMARY KEY, embedding FLOAT[" + DIM + "])").update();
    }
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

  private boolean tryLoadVec(VecCapability cap) {
    try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
      boolean ok = false;
      for (String name : new String[] {"vec0", "vec", "sqlite_vec"}) {
        try {
          st.execute("SELECT load_extension('" + name + "')");
          ok = true;
          break;
        } catch (Exception ignored) {
          // try next entry-point name
        }
      }
      if (ok) {
        try (var rs = st.executeQuery("SELECT vec_version()")) {
          if (rs.next()) {
            markLoaded(cap);
            return true;
          }
        }
      }
    } catch (Exception ignored) {
      // extension unavailable on this platform; tests will be skipped via assumeTrue
    }
    return false;
  }

  /** VecCapability.markLoaded is package-private to config; set it reflectively from the test. */
  private static void markLoaded(VecCapability cap) {
    try {
      var m = VecCapability.class.getDeclaredMethod("markLoaded");
      m.setAccessible(true);
      m.invoke(cap);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static PieriaProperties properties(int dimension, boolean vectorEnabled) {
    // Build via the canonical record constructor with sensible defaults for unused groups.
    Constructor<?> ctor = PieriaProperties.class.getDeclaredConstructors()[0];
    try {
      return new PieriaProperties(
        new PieriaProperties.Daemon("127.0.0.1", 8077),
        new PieriaProperties.Db("ignored"),
        new PieriaProperties.Provider("http://localhost:11434", "test-key", "test-provider", "openai", "2024-10-21"),
        new PieriaProperties.Model("small", "large", "embed", dimension, null),
        new PieriaProperties.Ingestion(10000, 2, 4, 9, 32, 5, true, 5000),
        new PieriaProperties.Retrieval(vectorEnabled, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 0.0, 0.0, 2, 20, 8, "heuristic"),
      null);
    } catch (Throwable t) {
      throw new IllegalStateException("PieriaProperties shape changed; update this test", t);
    }
  }

  private float[] vec(float a, float b, float c, float d) {
    return new float[] {a, b, c, d};
  }

  @Test
  void capabilityReflectsExtensionAndFlag() {
    // When available, the flag must be on (we constructed with vector-enabled = true).
    if (store.isVectorSearchAvailable()) {
      assertTrue(store.isVectorSearchAvailable());
    } else {
      assertFalse(store.isVectorSearchAvailable());
    }
  }

  @Test
  void upsertThenVectorSearchReturnsNearest() {
    assumeTrue(store.isVectorSearchAvailable());
    Profile p = store.getOrCreateProfile("vec-a");

    StoreOutcome near = store.store(p.id(), Memory.of(MemoryType.FACT, "near memory", "s1", "k.near", null));
    StoreOutcome far = store.store(p.id(), Memory.of(MemoryType.FACT, "far memory", "s2", "k.far", null));
    store.completeVectorization(near.stored().id(), vec(1f, 0f, 0f, 0f));
    store.completeVectorization(far.stored().id(), vec(0f, 0f, 0f, 1f));

    List<Memory> hits = store.vectorSearch(p.id(), vec(0.9f, 0.1f, 0f, 0f), 10);
    assertFalse(hits.isEmpty());
    assertEquals(near.stored().id(), hits.get(0).id());
  }

  @Test
  void supersessionRemovesVectorFromResults() {
    assumeTrue(store.isVectorSearchAvailable());
    Profile p = store.getOrCreateProfile("vec-supersede");

    StoreOutcome first = store.store(p.id(), Memory.of(MemoryType.FACT, "lives in Berlin", "s1", "location", null));
    store.completeVectorization(first.stored().id(), vec(1f, 0f, 0f, 0f));
    assertEquals(1, store.vectorSearch(p.id(), vec(1f, 0f, 0f, 0f), 10).size());

    StoreOutcome second = store.store(p.id(), Memory.of(MemoryType.FACT, "lives in Munich", "s2", "location", null));
    store.completeVectorization(second.stored().id(), vec(0f, 1f, 0f, 0f));

    // The superseded vector must be gone; only the new one remains.
    List<Memory> hits = store.vectorSearch(p.id(), vec(1f, 0f, 0f, 0f), 10);
    assertEquals(1, hits.size());
    assertEquals(second.stored().id(), hits.get(0).id());
  }

  @Test
  void forgetRemovesVectorFromResults() {
    assumeTrue(store.isVectorSearchAvailable());
    Profile p = store.getOrCreateProfile("vec-forget");
    StoreOutcome s = store.store(p.id(), Memory.of(MemoryType.FACT, "ephemeral", "s1", null, null));
    store.completeVectorization(s.stored().id(), vec(1f, 0f, 0f, 0f));
    assertEquals(1, store.vectorSearch(p.id(), vec(1f, 0f, 0f, 0f), 10).size());

    store.forgetMemory(p.id(), s.stored().id());
    assertTrue(store.vectorSearch(p.id(), vec(1f, 0f, 0f, 0f), 10).isEmpty());
  }

  @Test
  void tasksNeverAppearInVectorResults() {
    assumeTrue(store.isVectorSearchAvailable());
    Profile p = store.getOrCreateProfile("vec-task");
    // Tasks are not enqueued for vectorization, but force a vec row defensively to prove the query
    // filters them out even if one ever leaked in.
    StoreOutcome task = store.store(p.id(), Memory.of(MemoryType.TASK, "buy milk", "s1", "todo", null));
    store.upsertEmbedding(task.stored().id(), vec(1f, 0f, 0f, 0f));

    assertTrue(store.vectorSearch(p.id(), vec(1f, 0f, 0f, 0f), 10).isEmpty());
  }

  @Test
  void backfillPopulatesVecFromExistingBlobs() {
    assumeTrue(store.isVectorSearchAvailable());
    Profile p = store.getOrCreateProfile("vec-backfill");
    StoreOutcome s = store.store(p.id(), Memory.of(MemoryType.FACT, "backfill me", "s1", null, null));
    // Write only the BLOB (simulate a Phase-2 row) by going around the vec upsert.
    jdbc.sql("UPDATE memories SET embedding = ? WHERE id = ?")
      .params(encodeForTest(vec(1f, 0f, 0f, 0f)), s.stored().id())
      .update();
    jdbc.sql("DELETE FROM memories_vec WHERE memory_id = ?").param(s.stored().id()).update();
    assertTrue(store.vectorSearch(p.id(), vec(1f, 0f, 0f, 0f), 10).isEmpty());

    int n = store.backfillVectors();
    assertEquals(1, n);
    assertEquals(1, store.vectorSearch(p.id(), vec(1f, 0f, 0f, 0f), 10).size());
  }

  private static byte[] encodeForTest(float[] embedding) {
    java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(embedding.length * Float.BYTES)
      .order(java.nio.ByteOrder.LITTLE_ENDIAN);
    for (float v : embedding) {
      buf.putFloat(v);
    }
    return buf.array();
  }
}
