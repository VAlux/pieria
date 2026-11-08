package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.api.response.RevisionResponse;
import dev.alvo.pieria.domain.memory.IngestOrigin;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryOrigins;
import dev.alvo.pieria.domain.memory.MemoryType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Harvested memories may only be checked for conflicts against their own source. */
class ConflictCandidateScopeTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteMemoryStore store;
  private String profileId;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-conflict-scope-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    Flyway.configure().dataSource(dataSource).load().migrate();
    store = new SqliteMemoryStore(JdbcClient.create(dataSource));
    profileId = store.getOrCreateProfile("scope").id();
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

  private Memory insert(String content, String payload) {
    return store.insertMemory(profileId, memory(content, payload));
  }

  private static Memory memory(String content, String payload) {
    return new Memory(null, "s", MemoryType.FACT, content, null, null, false, payload, null,
      Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void harvestedIncomingSeesOnlyItsOwnSource() {
    insert("own postgres fact", "{}");
    Memory sameSource = insert("postgres from jira", stamped("jira-acme"));
    insert("postgres from wiki", stamped("wiki"));

    var ids = store.conflictCandidates(profileId, memory("postgres now", stamped("jira-acme")), 12)
      .stream().map(RevisionResponse::memoryId).toList();

    assertThat(ids).containsExactly(sameSource.id());
  }

  @Test
  void conversationIncomingSeesOnlyOwnMemories() {
    // A conversation fact joining a harvested memory's fact group would let the next harvest close it.
    Memory own = insert("own postgres fact", "{}");
    insert("postgres from jira", stamped("jira-acme"));

    var ids = store.conflictCandidates(profileId, memory("postgres now", "{}"), 12)
      .stream().map(RevisionResponse::memoryId).toList();

    assertThat(ids).containsExactly(own.id());
  }

  @Test
  void otherSourcesCannotCrowdOutHarvestedIncoming() {
    for (int i = 0; i < 20; i++) {
      insert("postgres from wiki " + i, stamped("wiki"));
    }
    Memory sameSource = insert("a much longer note that mentions postgres among many other unrelated words so it ranks below shorter rows", stamped("jira-acme"));

    var ids = store.conflictCandidates(profileId, memory("postgres now", stamped("jira-acme")), 12)
      .stream().map(RevisionResponse::memoryId).toList();

    assertThat(ids).containsExactly(sameSource.id());
  }

  @Test
  void harvestedRowsCannotCrowdOutConversationIncoming() {
    for (int i = 0; i < 20; i++) {
      insert("postgres from jira " + i, stamped("jira-acme"));
    }
    Memory own = insert("own postgres fact", "{}");

    var ids = store.conflictCandidates(profileId, memory("postgres now", "{}"), 12)
      .stream().map(RevisionResponse::memoryId).toList();

    assertThat(ids).containsExactly(own.id());
  }
}
