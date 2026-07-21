package dev.alvo.pieria.audit;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.tools.Hash;
import dev.alvo.pieria.api.request.AuditListRequest;
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

import static org.assertj.core.api.Assertions.assertThat;

class SqliteAuditStoreTests {
  private Path dbFile;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;
  private SqliteAuditStore store;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-audit-", ".db");
    dataSource = DataSourceBuilder.create().type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC").url("jdbc:sqlite:" + dbFile.toAbsolutePath()).build();
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = JdbcClient.create(dataSource);
    store = new SqliteAuditStore(jdbc);
    jdbc.sql("INSERT INTO profiles (id, name, created_at) VALUES ('p1', 'alpha', ?)")
      .param(Instant.now().toString()).update();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (dataSource != null) dataSource.close();
    if (dbFile != null) {
      Files.deleteIfExists(dbFile);
      Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-wal"));
      Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-shm"));
    }
  }

  @Test
  void appendsFindsFiltersAndFullTextSearches() {
    store.append(event("e1", "recall", "codex", "the answer mentions Pieria", false));
    store.append(event("e2", "memory.remember", "cli", "stored", true));

    assertThat(store.find("alpha", "e1")).isPresent();
    assertThat(search(new AuditQuery("Pieria", null, null, null, null, null, null,
      null, null, null, null, null, null, null, null, 10)))
      .extracting(AuditEvent::id).containsExactly("e1");
    assertThat(search(new AuditQuery(null, "memory.remember", "cli", null, null, "success", 201,
      "s1", null, null, null, null, true, null, null, 10)))
      .extracting(AuditEvent::id).containsExactly("e2");
  }

  @Test
  void deleteRemovesRowsAndFtsEntries() {
    store.append(event("e1", "recall", "gateway", "unique searchable output", false));
    store.deleteForProfile("p1", "alpha");

    assertThat(store.find("alpha", "e1")).isEmpty();
    assertThat(search(new AuditQuery("unique", null, null, null, null, null, null,
      null, null, null, null, null, null, null, null, 10))).isEmpty();
  }

  @Test
  void serviceCursorPagesWithoutDuplicates() {
    store.append(event("e1", "recall", "gateway", "one", false));
    store.append(event("e2", "recall", "gateway", "two", false));
    AuditService service = new AuditService(store);
    AuditListRequest firstRequest = new AuditListRequest(null, null, null, null, null, null,
      null, null, null, null, null, null, null, 1, null);
    var first = service.search("alpha", firstRequest);
    var second = service.search("alpha", new AuditListRequest(null, null, null, null, null, null,
      null, null, null, null, null, null, null, 1, first.nextCursor()));

    assertThat(first.events()).extracting(dev.alvo.pieria.api.response.AuditEventSummary::id)
      .containsExactly("e2");
    assertThat(second.events()).extracting(dev.alvo.pieria.api.response.AuditEventSummary::id)
      .containsExactly("e1");
    assertThat(second.nextCursor()).isNull();
  }

  private List<AuditEvent> search(AuditQuery query) {
    return store.search("alpha", query);
  }

  private static AuditEvent event(String id, String operation, String client, String response,
                                  boolean truncated) {
    Instant time = Instant.parse(id.equals("e1") ? "2026-01-01T00:00:00Z" : "2026-01-02T00:00:00Z");
    String emptyHash = Hash.sha256Hex(new byte[0]);
    return new AuditEvent(id, "p1", "alpha", "http", operation, "r-" + id, null, null,
      "s1", null, client, client.equals("codex") ? "codex" : null, "cli", "1", "1",
      "127.0.0.1", "POST", "/v1/profiles/alpha/test", null, "application/json",
      "application/json", time, time.plusMillis(12), 12, operation.equals("recall") ? 200 : 201,
      "success", null, null, "{}", "", 0, emptyHash, false, response,
      response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
      Hash.sha256Hex(response.getBytes(java.nio.charset.StandardCharsets.UTF_8)), truncated);
  }
}
