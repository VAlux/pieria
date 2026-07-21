package dev.alvo.pieria.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSourceConfigTests {

  private Path tempDir;
  private HikariDataSource dataSource;

  @AfterEach
  void tearDown() throws Exception {
    if (dataSource != null) {
      dataSource.close();
    }
    if (tempDir != null) {
      Path db = tempDir.resolve("pieria.db");
      Files.deleteIfExists(db);
      Files.deleteIfExists(Path.of(db + "-wal"));
      Files.deleteIfExists(Path.of(db + "-shm"));
      Files.deleteIfExists(tempDir);
    }
  }

  @Test
  void writeTransactionReservesWriterBeforeItsFirstRead() throws Exception {
    tempDir = Files.createTempDirectory("pieria-datasource-");
    Path db = tempDir.resolve("pieria.db");
    dataSource = (HikariDataSource) configuredDataSource(db);

    try (Connection first = dataSource.getConnection();
         Connection second = dataSource.getConnection()) {
      try (Statement setup = first.createStatement()) {
        setup.execute("CREATE TABLE test_memory (id INTEGER PRIMARY KEY, value TEXT)");
        setup.execute("INSERT INTO test_memory(value) VALUES ('seed')");
      }
      // Keep the competing-write assertion fast; production connections default to five seconds.
      try (Statement timeout = second.createStatement()) {
        timeout.execute("PRAGMA busy_timeout=1");
      }

      // Xerial executes BEGIN IMMEDIATE when Spring/JDBC disables auto-commit. The first connection
      // therefore owns the writer reservation before this SELECT opens a snapshot.
      first.setAutoCommit(false);
      try (Statement read = first.createStatement();
           ResultSet result = read.executeQuery("SELECT COUNT(*) FROM test_memory")) {
        assertTrue(result.next());
        assertEquals(1, result.getInt(1));
      }

      // A background vectorization-style write cannot commit between the SELECT and INSERT.
      try (Statement competingWrite = second.createStatement()) {
        SQLException busy = assertThrows(SQLException.class,
          () -> competingWrite.executeUpdate("UPDATE test_memory SET value = 'vectorized' WHERE id = 1"));
        assertEquals(5, busy.getErrorCode());
      }

      // The original transaction remains writable rather than failing with SQLITE_BUSY_SNAPSHOT.
      try (Statement write = first.createStatement()) {
        assertEquals(1, write.executeUpdate("INSERT INTO test_memory(value) VALUES ('onboarded')"));
      }
      first.commit();
    }
  }

  private DataSource configuredDataSource(Path db) {
    AppDataPathResolver paths = mock(AppDataPathResolver.class);
    when(paths.resolve()).thenReturn(new AppDataPathResolver.AppDataPaths(
      tempDir, tempDir, tempDir, tempDir, tempDir, db));
    VecExtensionResolver vec = mock(VecExtensionResolver.class);
    when(vec.resolve()).thenReturn(Optional.empty());
    return new DataSourceConfig().dataSource(paths, new DataSourceConfig.VecCapability(), vec);
  }
}
