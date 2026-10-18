package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.config.DataSourceConfig.VecCapability;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.VerifyMode;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.storage.MemoryStore.StoreOutcome;
import dev.alvo.pieria.testsupport.VecExtension;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sqlite-vec index behavior. Every assertion that depends on the native extension calls
 * {@link VecExtension#requireLoaded(boolean)}, which <em>fails</em> when the extension is missing
 * rather than skipping — see that class for why a skip was the wrong default.
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

  /**
   * Mirrors {@code DataSourceConfig.firstLoadableSql}: the bundled extension is tried first by
   * absolute path, with the bare entry-point names kept only as a fallback for machines that have
   * the library on the OS extension search path. Loading by bare name alone never resolves from
   * Gradle's working directory, which is what silently disabled this whole suite.
   */
  private boolean tryLoadVec(VecCapability cap) {
    List<String> argLists = new ArrayList<>();
    VecExtension.locate().ifPresent(path -> argLists.add("'" + path + "'"));
    argLists.addAll(List.of("'vec0'", "'vec'", "'sqlite_vec'", "'vec0', 'sqlite3_vec_init'"));
    try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
      boolean ok = false;
      for (String args : argLists) {
        try {
          st.execute("SELECT load_extension(" + args + ")");
          ok = true;
          break;
        } catch (Exception ignored) {
          // try next candidate
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
      // Extension unavailable; VecExtension.requireLoaded turns that into a failure.
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
        new PieriaProperties.Model("small", "large", "embed", dimension, 4, null, null),
        new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS,
          1, 0, 0, false, 3, 3, 32, 5, true, 5000, true, 0.70),
        new PieriaProperties.Retrieval(vectorEnabled, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 0.0, 0.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.60, 0.78,
          false, 0.4, true, 30, 400, 4000L),
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
    // The extension is required, and this store was constructed with vector-enabled = true, so the
    // capability must be on. Previously this branched on the very flag it meant to check, which made
    // it pass either way.
    VecExtension.requireLoaded(store.isVectorSearchAvailable());
    assertTrue(store.isVectorSearchAvailable());
  }

  @Test
  void upsertThenVectorSearchReturnsNearest() {
    VecExtension.requireLoaded(store.isVectorSearchAvailable());
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
  void semanticClaimHitFindsEligibleClusterWithoutLexicalOverlap() {
    VecExtension.requireLoaded(store.isVectorSearchAvailable());
    Profile p=store.getOrCreateProfile("claim-vector");
    var members=new ArrayList<dev.alvo.pieria.cluster.ClusterRepository.Member>();
    var sources=new ArrayList<dev.alvo.pieria.cluster.ClusterRepository.Source>();
    for(int i=0;i<3;i++) {
      Memory memory=store.store(p.id(),Memory.of(MemoryType.FACT,"writer detail "+i,
        "s"+i,"claim-test:"+i,null)).stored();
      members.add(new dev.alvo.pieria.cluster.ClusterRepository.Member("memory",memory.id()));
      String revision=jdbc.sql("SELECT id FROM assertion_revisions WHERE memory_id=?")
        .param(memory.id()).query(String.class).single();
      sources.add(new dev.alvo.pieria.cluster.ClusterRepository.Source(memory.id(),revision));
    }
    var group=store.writeClusters(repo->repo.create(p.id(),"Writer model","writer model",members,"test"));
    String fingerprint=store.readClusters(repo->repo.fingerprint(p.id(),group.id()));
    store.writeClusters(repo->repo.publish(p.id(),group.id(),group.revision(),fingerprint,
      "One writer coordinates storage.",List.of(new dev.alvo.pieria.cluster.ClusterRepository.Claim(
        "One writer coordinates storage.",sources,sources)),"fake","v1","{}","test",
      List.of(vec(1f,0f,0f,0f))));

    var snapshot=store.temporalSnapshot(null,null);
    assertEquals(1,store.searchClusterClaims(p.id(),"unrelated",vec(1f,0f,0f,0f),
      snapshot,false,10).size());
    assertTrue(store.searchClusterClaims(p.id(),"unrelated",vec(0f,0f,0f,1f),
      snapshot,false,10).isEmpty());
  }

  @Test
  void supersessionRemovesVectorFromResults() {
    VecExtension.requireLoaded(store.isVectorSearchAvailable());
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
    VecExtension.requireLoaded(store.isVectorSearchAvailable());
    Profile p = store.getOrCreateProfile("vec-forget");
    StoreOutcome s = store.store(p.id(), Memory.of(MemoryType.FACT, "ephemeral", "s1", null, null));
    store.completeVectorization(s.stored().id(), vec(1f, 0f, 0f, 0f));
    assertEquals(1, store.vectorSearch(p.id(), vec(1f, 0f, 0f, 0f), 10).size());

    store.forgetMemory(p.id(), s.stored().id());
    assertTrue(store.vectorSearch(p.id(), vec(1f, 0f, 0f, 0f), 10).isEmpty());
  }

  @Test
  void tasksNeverAppearInVectorResults() {
    VecExtension.requireLoaded(store.isVectorSearchAvailable());
    Profile p = store.getOrCreateProfile("vec-task");
    // Tasks are not enqueued for vectorization, but force a vec row defensively to prove the query
    // filters them out even if one ever leaked in.
    StoreOutcome task = store.store(p.id(), Memory.of(MemoryType.TASK, "buy milk", "s1", "todo", null));
    store.upsertEmbedding(task.stored().id(), vec(1f, 0f, 0f, 0f));

    assertTrue(store.vectorSearch(p.id(), vec(1f, 0f, 0f, 0f), 10).isEmpty());
  }

  @Test
  void temporalRankingFiltersBeforeLimitAtFiveAndFiftyThousandRows() {
    VecExtension.requireLoaded(store.isVectorSearchAvailable());
    for (int size : new int[]{5_000, 50_000}) {
      Profile p = store.getOrCreateProfile("temporal-scale-" + size);
      jdbc.sql("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?) "
          + "INSERT INTO memories(id, profile_id, type, content, payload, created_at, embedding) "
          + "SELECT ? || i, ?, 'fact', 'fixture temporal memory ' || i, '{}', '2020-01-01T00:00:00Z', "
          + "CASE WHEN i <= ? THEN ? ELSE ? END FROM n")
        .params(size, p.id() + ":", p.id(), size - 50, encodeForTest(vec(0f, 0f, 0f, 0f)),
          encodeForTest(vec(10f, 0f, 0f, 0f))).update();
      jdbc.sql("INSERT INTO memory_assertions(id, profile_id, source_occurrence, fact_group) "
          + "SELECT id, profile_id, id, id FROM memories WHERE profile_id = ?").param(p.id()).update();
      jdbc.sql("INSERT INTO assertion_revisions(id, assertion_id, memory_id, valid_from, recorded_from, from_sequence) "
          + "SELECT id, id, id, '2020-01-01T00:00:00.000000000Z', '2020-01-01T00:00:00.000000000Z', 0 "
          + "FROM memories WHERE profile_id = ?").param(p.id()).update();
      jdbc.sql("INSERT INTO validity_segments(revision_id, profile_id, fact_group, valid_from, valid_to, recorded_from, from_sequence) "
          + "SELECT id, profile_id, id, '2020-01-01T00:00:00.000000000Z', "
          + "CASE WHEN CAST(substr(id, length(?) + 2) AS INTEGER) <= ? THEN '2021-01-01T00:00:00.000000000Z' ELSE NULL END, "
          + "'2020-01-01T00:00:00.000000000Z', 0 FROM memories WHERE profile_id = ?")
        .params(p.id(), size - 50, p.id()).update();
      var current = store.temporalSnapshot(null, null);
      var historical = store.temporalSnapshot("2020-06-01T00:00:00Z", null);
      for (var snapshot : List.of(current, historical)) {
        long[] elapsed = new long[5];
        for (int run = 0; run < elapsed.length; run++) {
          long started = System.nanoTime();
          var hits = store.atSnapshot(snapshot, () -> store.vectorSearch(p.id(), vec(0f, 0f, 0f, 0f), 10));
          elapsed[run] = System.nanoTime() - started;
          assertEquals(10, hits.size(), "ineligible nearest vectors must not starve the limit");
          if (snapshot == current) assertTrue(hits.stream().allMatch(m -> Integer.parseInt(m.id().substring(p.id().length() + 1)) > size - 50));
        }
        Arrays.sort(elapsed);
        System.out.printf("TEMPORAL_BENCH rows=%d dimensions=4 mode=%s median_ms=%.3f max_ms=%.3f%n",
          size, snapshot == current ? "current" : "historical", elapsed[2] / 1e6, elapsed[4] / 1e6);
      }
    }
  }

  @Test
  void backfillPopulatesVecFromExistingBlobs() {
    VecExtension.requireLoaded(store.isVectorSearchAvailable());
    Profile p = store.getOrCreateProfile("vec-backfill");
    StoreOutcome s = store.store(p.id(), Memory.of(MemoryType.FACT, "backfill me", "s1", null, null));
    // Write only the BLOB (simulate a Phase-2 row) by going around the vec upsert.
    jdbc.sql("UPDATE memories SET embedding = ? WHERE id = ?")
      .params(encodeForTest(vec(1f, 0f, 0f, 0f)), s.stored().id())
      .update();
    jdbc.sql("DELETE FROM memories_vec WHERE memory_id = ?").param(s.stored().id()).update();
    assertEquals(1, store.vectorSearch(p.id(), vec(1f, 0f, 0f, 0f), 10).size());

    int n = store.backfillVectors();
    assertEquals(1, n);
    assertEquals(1, store.vectorSearch(p.id(), vec(1f, 0f, 0f, 0f), 10).size());
  }

  // Backs the retrieval-side semantic duplicate collapse, which needs the raw vectors rather than a
  // KNN. Reads the BLOB column directly, so unlike vectorSearch it does not need the vec extension.
  @Test
  void embeddingsForReturnsStoredVectorsRoundTripped() {
    Profile p = store.getOrCreateProfile("vec-embeddings");
    StoreOutcome a = store.store(p.id(), Memory.of(MemoryType.FACT, "vectorized", "s1", "k.a", null));
    StoreOutcome b = store.store(p.id(), Memory.of(MemoryType.FACT, "also vectorized", "s1", "k.b", null));
    StoreOutcome pending = store.store(p.id(), Memory.of(MemoryType.FACT, "not yet", "s1", "k.c", null));
    store.completeVectorization(a.stored().id(), vec(1f, 0f, 0f, 0f));
    store.completeVectorization(b.stored().id(), vec(0f, 1f, 0f, 0f));

    Map<String, float[]> found = store.embeddingsFor(p.id(),
      List.of(a.stored().id(), b.stored().id(), pending.stored().id(), "no-such-id"));

    assertEquals(2, found.size());
    assertArrayEquals(vec(1f, 0f, 0f, 0f), found.get(a.stored().id()));
    assertArrayEquals(vec(0f, 1f, 0f, 0f), found.get(b.stored().id()));
    // A memory awaiting vectorization is absent, not null-mapped — callers must read that as
    // "cannot compare", never as "not similar".
    assertFalse(found.containsKey(pending.stored().id()));
  }

  @Test
  void embeddingsForIsScopedToTheProfile() {
    Profile mine = store.getOrCreateProfile("vec-scope-mine");
    Profile other = store.getOrCreateProfile("vec-scope-other");
    StoreOutcome theirs = store.store(other.id(), Memory.of(MemoryType.FACT, "theirs", "s1", "k", null));
    store.completeVectorization(theirs.stored().id(), vec(1f, 0f, 0f, 0f));

    assertTrue(store.embeddingsFor(mine.id(), List.of(theirs.stored().id())).isEmpty());
  }

  @Test
  void embeddingsForHandlesEmptyAndDuplicateInput() {
    Profile p = store.getOrCreateProfile("vec-empty");
    StoreOutcome a = store.store(p.id(), Memory.of(MemoryType.FACT, "dup input", "s1", "k.a", null));
    store.completeVectorization(a.stored().id(), vec(1f, 0f, 0f, 0f));

    assertTrue(store.embeddingsFor(p.id(), List.of()).isEmpty());
    // Repeats must not multiply the IN-list placeholders past the bound parameters.
    assertEquals(1, store.embeddingsFor(p.id(),
      List.of(a.stored().id(), a.stored().id(), a.stored().id())).size());
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
