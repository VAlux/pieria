package dev.alvo.pieria.api.controller;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.api.request.CodeIndexRequest;
import dev.alvo.pieria.api.request.CodeIndexRequest.FileDto;
import dev.alvo.pieria.api.response.CodeIndexResponse;
import dev.alvo.pieria.api.response.CodeStatusResponse;
import dev.alvo.pieria.code.CodeIndexingService;
import dev.alvo.pieria.code.CodeParser.ParseResult;
import dev.alvo.pieria.code.CodeParser.ParsedSymbol;
import dev.alvo.pieria.code.FakeCodeParser;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.storage.SqliteCodeIndexStore;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import dev.alvo.pieria.task.TaskRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CodeController}: the POST endpoint maps the request through the indexing service
 * and returns per-run counts; the status endpoint reflects stored counts and reports absence cleanly.
 */
class CodeControllerTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private CodeController controller;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-codectl-test-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(dataSource).load().migrate();

    JdbcClient jdbc = JdbcClient.create(dataSource);
    SqliteMemoryStore memoryStore = new SqliteMemoryStore(jdbc);
    SqliteCodeIndexStore codeStore = new SqliteCodeIndexStore(jdbc);

    FakeCodeParser parser = new FakeCodeParser("java").register("Bar.java", new ParseResult(
      List.of(
        new ParsedSymbol(CodeSymbolKind.CLASS, "Bar", "Bar", "class Bar", "public", 1, 20, null),
        new ParsedSymbol(CodeSymbolKind.METHOD, "create", "Bar#create", "create()", "public", 5, 9, "Bar")),
      List.of()));

    CodeIndexingService service = new CodeIndexingService(memoryStore, codeStore, List.of(parser),
      new DataSourceTransactionManager(dataSource));
    controller = new CodeController(service, codeStore, memoryStore,
      JsonMapper.builder().build(), new TaskRegistry());
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
  void indexReturnsCountsAndStatusReflectsThem() {
    CodeIndexRequest request = new CodeIndexRequest("tree1",
      List.of(new FileDto("Bar.java", "java", "h1", "class Bar {}")));

    CodeIndexResponse response = controller.index("code", request);
    assertThat(response.filesParsed()).isEqualTo(1);
    assertThat(response.symbols()).isEqualTo(2);
    assertThat(response.memoriesStored()).isEqualTo(1);

    CodeStatusResponse status = controller.status("code");
    assertThat(status.present()).isTrue();
    assertThat(status.files()).isEqualTo(1);
    assertThat(status.symbols()).isEqualTo(2);
  }

  @Test
  void statusForUnknownProfileReportsAbsent() {
    CodeStatusResponse status = controller.status("never-indexed");
    assertThat(status.present()).isFalse();
    assertThat(status.files()).isZero();
  }
}
