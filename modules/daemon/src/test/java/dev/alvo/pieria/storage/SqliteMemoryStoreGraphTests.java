package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.domain.ContentId;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.profile.Profile;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network-free integration test for the graph surface of {@link SqliteMemoryStore}: V4
 * migration, idempotent entity/edge upserts, transactional graph persistence via
 * {@link MemoryStore#store(String, Memory, GraphFragment)}, bounded neighborhood expansion, and
 * supersession-aware traversal (edges off superseded memories must never surface).
 */
class SqliteMemoryStoreGraphTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;
  private SqliteMemoryStore store;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-graph-test-", ".db");
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

  /** Two-entity fragment joined by one edge, names already normalized. */
  private static GraphFragment frag(String source, String relation, String target) {
    return new GraphFragment(
      List.of(Entity.of("concept", source, "{}"), Entity.of("concept", target, "{}")),
      List.of(new GraphFragment.EdgeTriple(source, "concept", relation, target, "concept")));
  }

  private String entityId(String profileId, String name) {
    List<Entity> found = store.findEntitiesByName(profileId, List.of(name), 1);
    assertEquals(1, found.size(), "expected a single entity named " + name);
    return found.get(0).id();
  }

  private long edgeCount(String profileId) {
    return jdbc.sql("SELECT COUNT(*) FROM edges WHERE profile_id = ?").param(profileId)
      .query(Long.class).single();
  }

  @Test
  void migrationCreatesGraphTablesAndIndexes() {
    List<String> tables = jdbc.sql("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
      .query(String.class).list();
    assertTrue(tables.contains("entities"));
    assertTrue(tables.contains("edges"));

    List<String> indexes = jdbc.sql("SELECT name FROM sqlite_master WHERE type = 'index'")
      .query(String.class).list();
    assertTrue(indexes.contains("idx_entity_profile_type_name"));
    assertTrue(indexes.contains("idx_edge_source"));
    assertTrue(indexes.contains("idx_edge_memory"));
  }

  @Test
  void upsertEntityComputesContentAddressedIdAndIsIdempotent() {
    Profile p = store.getOrCreateProfile("g-upsert");
    Entity first = store.upsertEntity(p.id(), Entity.of("tool", "redis", "{}"));
    Entity second = store.upsertEntity(p.id(), Entity.of("tool", "redis", "{}"));

    assertEquals(ContentId.forEntity(p.id(), "tool", "redis"), first.id());
    assertEquals(first.id(), second.id());

    long count = jdbc.sql("SELECT COUNT(*) FROM entities WHERE profile_id = ?").param(p.id())
      .query(Long.class).single();
    assertEquals(1L, count);
  }

  @Test
  void storePersistsGraphInSameTransactionIdempotently() {
    Profile p = store.getOrCreateProfile("g-store");
    Memory mem = Memory.of(MemoryType.FACT, "alpha uses beta", "s1", null, null);

    store.store(p.id(), mem, frag("alpha", "uses", "beta"));
    // Re-ingest the identical memory + graph: content-addressed ids keep it idempotent.
    store.store(p.id(), mem, frag("alpha", "uses", "beta"));

    long entities = jdbc.sql("SELECT COUNT(*) FROM entities WHERE profile_id = ?").param(p.id())
      .query(Long.class).single();
    assertEquals(2L, entities);
    assertEquals(1L, edgeCount(p.id()));
  }

  @Test
  void entitiesForMemoriesReturnsBothEndpoints() {
    Profile p = store.getOrCreateProfile("g-e4m");
    MemoryStore.StoreOutcome outcome =
      store.store(p.id(), Memory.of(MemoryType.FACT, "alpha uses beta", "s1", null, null),
        frag("alpha", "uses", "beta"));

    List<Entity> entities = store.entitiesForMemories(p.id(), List.of(outcome.stored().id()), 10);
    assertEquals(2, entities.size());
    assertTrue(entities.stream().anyMatch(e -> e.name().equals("alpha")));
    assertTrue(entities.stream().anyMatch(e -> e.name().equals("beta")));
  }

  @Test
  void neighborhoodExpandsBoundedByDepth() {
    Profile p = store.getOrCreateProfile("g-hop");
    store.store(p.id(), Memory.of(MemoryType.FACT, "alpha rel beta", "s1", null, null), frag("alpha", "rel", "beta"));
    store.store(p.id(), Memory.of(MemoryType.FACT, "beta rel gamma", "s2", null, null), frag("beta", "rel", "gamma"));

    String alpha = entityId(p.id(), "alpha");
    String beta = entityId(p.id(), "beta");
    String gamma = entityId(p.id(), "gamma");

    List<String> depth1 = store.neighborhood(p.id(), List.of(alpha), 1, 50);
    assertTrue(depth1.contains(alpha));
    assertTrue(depth1.contains(beta));
    assertFalse(depth1.contains(gamma), "gamma is two hops away");

    List<String> depth2 = store.neighborhood(p.id(), List.of(alpha), 2, 50);
    assertTrue(depth2.contains(gamma), "gamma is reachable within two hops");
  }

  @Test
  void supersededMemoryEdgesNeverSurface() {
    Profile p = store.getOrCreateProfile("g-supersede");
    // Both facts share topic key "k"; the second supersedes the first. Both touch entity "redis".
    store.store(p.id(), Memory.of(MemoryType.FACT, "old uses redis", "s1", "k", null), frag("old", "uses", "redis"));
    store.store(p.id(), Memory.of(MemoryType.FACT, "new uses redis", "s2", "k", null), frag("new", "uses", "redis"));

    String redis = entityId(p.id(), "redis");

    // findMemoriesByEntities: only the active "new uses redis" memory surfaces.
    List<Memory> memories = store.findMemoriesByEntities(p.id(), List.of(redis), 10);
    assertEquals(1, memories.size());
    assertTrue(memories.get(0).content().contains("new"));

    // neighborhood from redis reaches "new" (active edge) but not "old" (edge off superseded memory).
    String newEntity = entityId(p.id(), "new");
    List<String> reached = store.neighborhood(p.id(), List.of(redis), 1, 50);
    assertTrue(reached.contains(newEntity));

    List<Entity> old = store.findEntitiesByName(p.id(), List.of("old"), 1);
    assertEquals(1, old.size(), "the old entity row is retained (not physically deleted)");
    assertFalse(reached.contains(old.get(0).id()), "but its edge is off a superseded memory");
  }

  @Test
  void graphSnapshotReturnsConnectedNodesAndActiveEdgesWithProvenance() {
    Profile p = store.getOrCreateProfile("g-snapshot");
    store.store(p.id(), Memory.of(MemoryType.FACT, "alpha uses beta", "s1", null, null), frag("alpha", "uses", "beta"));

    var snapshot = store.graphSnapshot(p.id());

    assertEquals(2, snapshot.nodes().size());
    assertTrue(snapshot.nodes().stream().anyMatch(n -> n.name().equals("alpha")));
    assertTrue(snapshot.nodes().stream().anyMatch(n -> n.name().equals("beta")));

    assertEquals(1, snapshot.links().size());
    var link = snapshot.links().get(0);
    assertEquals("uses", link.relation());
    assertEquals(entityId(p.id(), "alpha"), link.sourceEntityId());
    assertEquals(entityId(p.id(), "beta"), link.targetEntityId());
    assertEquals("alpha uses beta", link.memoryContent());
  }

  @Test
  void graphSnapshotOmitsSupersededEdgesAndNowIsolatedNodes() {
    Profile p = store.getOrCreateProfile("g-snapshot-supersede");
    // Both share topic key "k": the second supersedes the first. "old" only ever appears on the
    // now-superseded edge, so it must drop out of the snapshot entirely.
    store.store(p.id(), Memory.of(MemoryType.FACT, "old uses redis", "s1", "k", null), frag("old", "uses", "redis"));
    store.store(p.id(), Memory.of(MemoryType.FACT, "new uses redis", "s2", "k", null), frag("new", "uses", "redis"));

    var snapshot = store.graphSnapshot(p.id());

    assertEquals(1, snapshot.links().size(), "only the active edge survives");
    assertEquals(2, snapshot.nodes().size(), "redis + new; old is isolated once its edge is gone");
    assertFalse(snapshot.nodes().stream().anyMatch(n -> n.name().equals("old")));
    assertTrue(snapshot.nodes().stream().anyMatch(n -> n.name().equals("new")));
    assertTrue(snapshot.nodes().stream().anyMatch(n -> n.name().equals("redis")));
  }

  @Test
  void findMemoriesByEntitiesRanksByProximityThenRecency() {
    Profile p = store.getOrCreateProfile("g-rank");
    MemoryStore.StoreOutcome first =
      store.store(p.id(), Memory.of(MemoryType.FACT, "alpha uses beta", "s1", null, null), frag("alpha", "uses", "beta"));
    MemoryStore.StoreOutcome second =
      store.store(p.id(), Memory.of(MemoryType.FACT, "gamma uses delta", "s2", null, null), frag("gamma", "uses", "delta"));

    String alpha = entityId(p.id(), "alpha");
    String gamma = entityId(p.id(), "gamma");

    // Earliest-listed touching entity wins: alpha's memory ranks before gamma's.
    List<Memory> ordered = store.findMemoriesByEntities(p.id(), List.of(alpha, gamma), 10);
    assertEquals(first.stored().id(), ordered.get(0).id());
    assertEquals(second.stored().id(), ordered.get(1).id());
  }

  @Test
  void findGraphOrphansReturnsEdgelessActiveNonTaskMemories() {
    Profile p = store.getOrCreateProfile("g-orphans");
    // Edgeless (stored 2-arg) → orphan.
    MemoryStore.StoreOutcome orphan =
      store.store(p.id(), Memory.of(MemoryType.FACT, "orphan fact", "s1", null, null));
    // Has a graph fragment → not an orphan.
    store.store(p.id(), Memory.of(MemoryType.FACT, "alpha uses beta", "s2", null, null), frag("alpha", "uses", "beta"));
    // TASK memories are excluded from graph extraction, so never orphans.
    store.store(p.id(), Memory.of(MemoryType.TASK, "do the thing", "s3", null, null));

    assertEquals(1L, store.countGraphOrphans(p.id()));
    List<Memory> orphans = store.findGraphOrphans(p.id(), 100);
    assertEquals(1, orphans.size());
    assertEquals(orphan.stored().id(), orphans.get(0).id());
  }

  @Test
  void attachGraphAddsEdgesStampsAdoptionAndIsIdempotent() {
    Profile p = store.getOrCreateProfile("g-attach");
    MemoryStore.StoreOutcome orphan =
      store.store(p.id(), Memory.of(MemoryType.FACT, "alpha uses beta", "s1", null, null));

    store.attachGraph(p.id(), orphan.stored().id(), frag("alpha", "uses", "beta"));
    // Re-attaching the same fragment is idempotent (content-addressed insert-or-ignore).
    store.attachGraph(p.id(), orphan.stored().id(), frag("alpha", "uses", "beta"));

    assertEquals(1L, edgeCount(p.id()));
    assertEquals(1, store.graphSnapshot(p.id()).links().size());
    // Adopted → no longer an orphan.
    assertEquals(0L, store.countGraphOrphans(p.id()));
    assertTrue(store.findGraphOrphans(p.id(), 100).isEmpty());
  }

  @Test
  void attachGraphEmptyFragmentStillStampsSoItIsNeverReprocessed() {
    Profile p = store.getOrCreateProfile("g-attach-empty");
    MemoryStore.StoreOutcome orphan =
      store.store(p.id(), Memory.of(MemoryType.FACT, "trivial fact", "s1", null, null));

    assertEquals(1L, store.countGraphOrphans(p.id()));
    store.attachGraph(p.id(), orphan.stored().id(), GraphFragment.empty());

    // No edges created, but the memory is stamped and drops out of the orphan set.
    assertEquals(0L, edgeCount(p.id()));
    assertEquals(0L, store.countGraphOrphans(p.id()));
  }
}
