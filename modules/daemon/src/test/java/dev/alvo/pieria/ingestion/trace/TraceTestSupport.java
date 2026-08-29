package dev.alvo.pieria.ingestion.trace;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.config.DataSourceConfig.VecCapability;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.VerifyMode;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import org.flywaydb.core.Flyway;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Path;

/**
 * Shared on-disk {@code SqliteMemoryStore} + {@code PieriaProperties} construction for trace-package
 * tests. Package-private on purpose: both {@code TraceIngestionServiceTests} (this task) and a later
 * test class (Task 15) need the same two helpers, and a private nested helper in one test class would
 * be unreachable from the other.
 *
 * <p>Mirrors the construction in {@code SqliteMemoryStoreVectorTests} (around line 142), minus the
 * {@code sqlite-vec} extension loading that class does for its own vector-index assertions — the
 * trace path never touches vector search directly.
 */
final class TraceTestSupport {

  private TraceTestSupport() {
  }

  /** An on-disk {@code SqliteMemoryStore} backed by a fresh, migrated database at {@code dbFile}. */
  static SqliteMemoryStore newSqliteStore(Path dbFile) {
    HikariDataSource dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath() + "?enable_load_extension=true")
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");

    Flyway.configure().dataSource(dataSource).load().migrate();

    JdbcClient jdbc = JdbcClient.create(dataSource);
    PieriaProperties props = defaultPieriaProperties();
    return new SqliteMemoryStore(jdbc, new VecCapability(), props);
  }

  /** {@code PieriaProperties} built via the canonical record constructor with test-sensible defaults. */
  static PieriaProperties defaultPieriaProperties() {
    return new PieriaProperties(
      new PieriaProperties.Daemon("127.0.0.1", 8077),
      new PieriaProperties.Db("ignored"),
      new PieriaProperties.Provider(
        "http://localhost:11434", "test-key", "test-provider", "openai", "2024-10-21"),
      new PieriaProperties.Model("small", "large", "embed", 4, 4, null, null),
      new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS,
        1, 0, 0, false, 3, 3, 32, 5, true, 5000, true, 0.70),
      new PieriaProperties.Retrieval(true, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000,
        0.0, 0.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.60, 0.78),
      null);
  }
}
