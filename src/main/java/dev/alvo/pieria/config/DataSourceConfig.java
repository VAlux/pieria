package dev.alvo.pieria.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the embedded SQLite {@link DataSource} from {@code pieria.db.path}, creating the
 * parent directory if needed and enabling WAL mode (concurrent reads under the single writer,
 * SPEC 5.2 / 11). Flyway and the application share this datasource.
 */
@Configuration
public class DataSourceConfig {

  private static void ensureParentDirectory(String dbPath) {
    Path parent = Path.of(dbPath).toAbsolutePath().getParent();
    if (parent != null) {
      try {
        Files.createDirectories(parent);
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot create database directory: " + parent, e);
      }
    }
  }

  @Bean
  @Primary
  public DataSource dataSource(PieriaProperties properties) {
    String path = properties.db().path();
    ensureParentDirectory(path);

    HikariDataSource dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + path)
      .build();

    // SQLite is single-writer; WAL lets readers proceed during a write. Applied per connection.
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    return dataSource;
  }
}
