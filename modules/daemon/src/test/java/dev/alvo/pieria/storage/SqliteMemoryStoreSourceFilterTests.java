package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.domain.memory.IngestOrigin;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryOrigins;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.domain.memory.SourceFilter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Source filters applied in the channel SQL, against a real migrated SQLite file. */
class SqliteMemoryStoreSourceFilterTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteMemoryStore store;
  private String pid;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-source-filter-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    Flyway.configure().dataSource(dataSource).load().migrate();
    store = new SqliteMemoryStore(JdbcClient.create(dataSource));
    pid = store.getOrCreateProfile("filter").id();
  }

  @AfterEach
  void tearDown() throws Exception {
    dataSource.close();
    Files.deleteIfExists(dbFile);
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-wal"));
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-shm"));
  }

  private static String stamped(String sourceId) {
    return MemoryOrigins.withOrigin("{}", new IngestOrigin(sourceId, "jira", "1", null));
  }

  private Memory insert(String content, String session, String key, String payload) {
    return store.insertMemory(pid, new Memory(null, session, MemoryType.FACT, content, key, null, false, payload,
      null, Instant.parse("2026-01-01T00:00:00Z")));
  }

  private List<Memory> filtered(SourceFilter filter, Supplier<List<Memory>> query) {
    return store.atSourceFilter(filter, query);
  }

  private static SourceFilter including(String id) {
    return SourceFilter.of(List.of(id), List.of());
  }

  private static SourceFilter excluding(String id) {
    return SourceFilter.of(List.of(), List.of(id));
  }

  @Test
  void ftsExcludeDoesNotStarveTheLimit() {
    for (int i = 0; i < 30; i++) {
      insert("tea note " + i, "s", null, stamped("jira"));
    }
    Memory own = insert("tea preference", "s", null, "{}");

    assertThat(filtered(excluding("jira"), () -> store.searchMemoriesFts(pid, "tea", 1)))
      .extracting(Memory::id).containsExactly(own.id());
  }

  @Test
  void includeAppliesToExactKeyLookup() {
    insert("own value", "s", "key.own", "{}");
    Memory harvested = insert("jira value", "s", "key.jira", stamped("jira"));

    assertThat(filtered(including("jira"), () -> store.exactKeyLookup(pid, List.of("key.own", "key.jira"), 5)))
      .extracting(Memory::id).containsExactly(harvested.id());
  }

  @Test
  void includeAppliesToMessageFts() {
    store.insertMessages(pid, "s1", List.of(new Message(null, "s1", "user", "we discussed oolong", null)));
    insert("own value", "s1", null, "{}");
    Memory harvested = insert("jira value", "s1", null, stamped("jira"));

    assertThat(filtered(including("jira"), () -> store.searchMemoriesByMessageFts(pid, "oolong", 5)))
      .extracting(Memory::id).containsExactly(harvested.id());
  }

  @Test
  void unknownSourceReturnsNothingWithoutError() {
    insert("tea preference", "s", null, "{}");

    assertThat(filtered(including("nope"), () -> store.searchMemoriesFts(pid, "tea", 5))).isEmpty();
  }

  @Test
  void unboundFilterChangesNothing() {
    insert("tea preference", "s", null, "{}");
    insert("tea from jira", "s", null, stamped("jira"));

    assertThat(store.searchMemoriesFts(pid, "tea", 50)).hasSize(2);
  }

  @Test
  void opaquePayloadSurvivesFilteredQueries() {
    Memory opaque = insert("tea opaque", "s", null, "not json at all");

    assertThat(filtered(excluding("jira"), () -> store.searchMemoriesFts(pid, "tea", 5)))
      .extracting(Memory::id).containsExactly(opaque.id());
  }
}
