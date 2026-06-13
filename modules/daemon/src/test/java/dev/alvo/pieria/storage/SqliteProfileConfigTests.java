package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.domain.profile.Profile;
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
 * Fast, network-free integration test for the per-profile config rows (V6 migration +
 * put/get/clearProfileConfig), following the {@link SqliteMemoryStoreTests} harness pattern.
 */
class SqliteProfileConfigTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteMemoryStore store;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-test-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");

    Flyway.configure().dataSource(dataSource).load().migrate();

    store = new SqliteMemoryStore(JdbcClient.create(dataSource));
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
  void getReturnsEmptyWhenNoConfigStored() {
    Profile p = store.getOrCreateProfile("cfg-none");
    assertTrue(store.getProfileConfig(p.id()).isEmpty());
  }

  @Test
  void putThenGetRoundTrips() {
    Profile p = store.getOrCreateProfile("cfg-roundtrip");
    String json = "{\"retrieval\":{\"weight-graph\":0.0}}";

    store.putProfileConfig(p.id(), json);

    assertEquals(json, store.getProfileConfig(p.id()).orElseThrow());
  }

  @Test
  void putReplacesExistingConfigWholesale() {
    Profile p = store.getOrCreateProfile("cfg-replace");
    store.putProfileConfig(p.id(), "{\"retrieval\":{\"rrf-k\":30}}");

    store.putProfileConfig(p.id(), "{\"ingestion\":{\"chunk-size-chars\":8000}}");

    assertEquals("{\"ingestion\":{\"chunk-size-chars\":8000}}",
      store.getProfileConfig(p.id()).orElseThrow());
  }

  @Test
  void clearRemovesConfig() {
    Profile p = store.getOrCreateProfile("cfg-clear");
    store.putProfileConfig(p.id(), "{}");

    store.clearProfileConfig(p.id());

    assertTrue(store.getProfileConfig(p.id()).isEmpty());
  }

  @Test
  void configIsIsolatedPerProfile() {
    Profile a = store.getOrCreateProfile("cfg-a");
    Profile b = store.getOrCreateProfile("cfg-b");
    store.putProfileConfig(a.id(), "{\"retrieval\":{\"rrf-k\":30}}");

    assertTrue(store.getProfileConfig(b.id()).isEmpty());
    store.clearProfileConfig(b.id());
    assertEquals("{\"retrieval\":{\"rrf-k\":30}}", store.getProfileConfig(a.id()).orElseThrow());
  }
}
