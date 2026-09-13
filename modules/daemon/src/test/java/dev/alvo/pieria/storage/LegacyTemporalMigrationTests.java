package dev.alvo.pieria.storage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LegacyTemporalMigrationTests {
  @TempDir Path dir;

  @Test void reconstructsLinkedHistoryAndExcludesAmbiguousRowsWithoutModelCalls() {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("legacy.db"));
    Flyway.configure().dataSource(ds).target("1").load().migrate();
    var jdbc = JdbcClient.create(ds);
    jdbc.sql("INSERT INTO profiles VALUES ('p', 'legacy', '2026-01-01T00:00:00Z')").update();
    jdbc.sql("INSERT INTO memories(id, profile_id, type, content, topic_key, payload, created_at, superseded) VALUES "
      + "('alice', 'p', 'fact', 'Alice owns service', 'owner', '{\"stated_at\":\"2026-01-01T00:00:00Z\"}', '2026-02-01T00:00:00Z', 1),"
      + "('bob', 'p', 'fact', 'Bob owns service', 'owner', '{\"stated_at\":\"2026-03-01T00:00:00Z\"}', '2026-04-01T00:00:00Z', 0),"
      + "('ambiguous', 'p', 'fact', 'ambiguous forgotten fact', 'other', '{}', '2026-01-01T00:00:00Z', 1)").update();
    jdbc.sql("UPDATE memories SET supersedes = 'alice' WHERE id = 'bob'").update();
    Flyway.configure().dataSource(ds).load().migrate();
    var store = new SqliteMemoryStore(jdbc);
    var beforeLearning = store.temporalSnapshot("2026-05-01T00:00:00Z", "2026-03-01T00:00:00Z");
    assertEquals(List.of("alice"), store.atSnapshot(beforeLearning, () -> store.exactKeyLookup("p", List.of("owner"), 10))
      .stream().map(m -> m.id()).toList());
    var afterLearning = store.temporalSnapshot("2026-05-01T00:00:00Z", "2026-05-01T00:00:00Z");
    assertEquals(List.of("bob"), store.atSnapshot(afterLearning, () -> store.exactKeyLookup("p", List.of("owner"), 10))
      .stream().map(m -> m.id()).toList());
    assertTrue(store.atSnapshot(afterLearning, () -> store.searchMemoriesFts("p", "ambiguous", 10)).isEmpty());
    assertTrue(store.selectedValidity("bob", afterLearning).getFirst().approximate());
    assertTrue(store.revisions("p", "alice").getFirst().approximate());
    assertEquals(List.of("bob"), store.listMemories("p", null, null).stream().map(m -> m.id()).toList());
    assertEquals(2, store.drainOutbox(10).size());
    assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM temporal_coverage WHERE profile_id = 'p'").query(Integer.class).single());
  }
}
