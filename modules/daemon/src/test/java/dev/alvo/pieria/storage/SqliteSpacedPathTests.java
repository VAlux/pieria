package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DataSourceConfig} concatenates the resolved database path straight into a
 * {@code jdbc:sqlite:<path>?<params>} URL. On Windows the default install root sits under
 * {@code %LOCALAPPDATA%}, so a user named "First Last" produces a path with a space in it — the
 * common case, not the exception. Pin that the driver accepts it, query parameters and all.
 */
class SqliteSpacedPathTests {

  @Test
  void opensAndMigratesADatabaseUnderAPathContainingSpaces(@TempDir Path tmp) throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("First Last").resolve("Local App Data"));
    Path dbFile = dir.resolve("pieria.db");

    String url = "jdbc:sqlite:" + dbFile.toAbsolutePath()
      + "?busy_timeout=5000&transaction_mode=IMMEDIATE";
    try (HikariDataSource dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url(url)
      .build()) {
      Flyway.configure().dataSource(dataSource).load().migrate();

      assertThat(JdbcClient.create(dataSource).sql("SELECT 1").query(Integer.class).single())
        .isEqualTo(1);
    }
    assertThat(dbFile).exists();
  }
}
