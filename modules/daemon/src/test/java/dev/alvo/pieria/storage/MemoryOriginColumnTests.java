package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
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

/** The generated {@code origin_source_id} column against a real migrated SQLite file. */
class MemoryOriginColumnTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;
  private SqliteMemoryStore store;
  private String profileId;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-origin-test-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = JdbcClient.create(dataSource);
    store = new SqliteMemoryStore(jdbc);
    profileId = store.getOrCreateProfile("origin").id();
  }

  @AfterEach
  void tearDown() throws Exception {
    dataSource.close();
    Files.deleteIfExists(dbFile);
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-wal"));
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-shm"));
  }

  private Memory insert(String content, String payload) {
    return store.insertMemory(profileId, new Memory(null, "s", MemoryType.FACT, content, null, null, false,
      payload, null, Instant.parse("2026-01-01T00:00:00Z")));
  }

  private String originOf(String memoryId) {
    return jdbc.sql("SELECT origin_source_id FROM memories WHERE id = ?")
      .param(memoryId)
      .query(String.class)
      .optional()
      .orElse(null);
  }

  @Test
  void generatedColumnExposesOriginAndToleratesOpaquePayloads() {
    Memory harvested = insert("a",
      MemoryOrigins.withOrigin("{}", new IngestOrigin("jira-acme", "jira", "1", null)));
    Memory opaque = insert("b", "not json at all");

    assertThat(originOf(harvested.id())).isEqualTo("jira-acme");
    assertThat(originOf(opaque.id())).isNull();
  }
}
