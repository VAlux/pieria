package dev.alvo.pieria.api.controller;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.api.response.TaskSubmitResponse;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.reminiscence.ReminiscenceService;
import dev.alvo.pieria.config.ReminiscenceProperties;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import dev.alvo.pieria.task.TaskRegistry;
import dev.alvo.pieria.task.TaskSnapshot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ReminiscenceController}: the async endpoint submits an adoption task whose
 * terminal result carries the run counts, and the dry-run endpoint reports the orphan count without
 * running anything. Verifies routing/DTO wiring only (per the controller-service layering rule).
 */
class ReminiscenceControllerTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteMemoryStore store;
  private TaskRegistry tasks;
  private ReminiscenceController controller;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-reminctl-test-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(dataSource).load().migrate();

    store = new SqliteMemoryStore(JdbcClient.create(dataSource));
    tasks = new TaskRegistry();
    ReminiscenceService service =
      new ReminiscenceService(store, new FakeModelGateway(), new ReminiscenceProperties(8, 6000, 500, 4));
    controller = new ReminiscenceController(service, tasks, JsonMapper.builder().build());
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

  private Profile seed(String profileName, String... contents) {
    Profile p = store.getOrCreateProfile(profileName);
    int s = 0;
    for (String content : contents) {
      store.store(p.id(), Memory.of(MemoryType.FACT, content, "s" + s++, null, null));
    }
    return p;
  }

  private TaskSnapshot awaitTerminal(String taskId) throws InterruptedException {
    UUID id = UUID.fromString(taskId);
    for (int i = 0; i < 100; i++) {
      TaskSnapshot snapshot = tasks.find(id).orElseThrow();
      if (!"RUNNING".equals(snapshot.status().name())) {
        return snapshot;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("task did not reach a terminal state in time");
  }

  @Test
  void asyncEndpointRunsAdoptionAndReportsCounts() throws InterruptedException {
    Profile p = seed("ctl-adopt", "alpha beta one", "delta epsilon two");

    TaskSubmitResponse submitted = controller.reminisceAsync("ctl-adopt", null);
    TaskSnapshot terminal = awaitTerminal(submitted.taskId());

    assertThat(terminal.status().name()).isEqualTo("SUCCEEDED");
    assertThat(terminal.result().get("memoriesScanned").asInt()).isEqualTo(2);
    assertThat(terminal.result().get("memoriesAdopted").asInt()).isEqualTo(2);
    assertThat(store.graphCounts(p.id()).edgeCount()).isPositive();
  }

  @Test
  void orphansEndpointReportsCountWithoutAdopting() {
    Profile p = seed("ctl-count", "alpha beta one", "delta epsilon two", "eta theta three");

    assertThat(controller.orphans("ctl-count").orphans()).isEqualTo(3L);
    // Dry-run only: nothing was adopted.
    assertThat(store.graphCounts(p.id()).edgeCount()).isZero();
    assertThat(store.countGraphOrphans(p.id())).isEqualTo(3L);
  }
}
