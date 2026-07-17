package dev.alvo.pieria.reminiscence;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.config.ReminiscenceProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.onboarding.ContentIngestor;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.model.ModelUnavailableException;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import dev.alvo.pieria.task.TaskCancelledException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network-free test for {@link ReminiscenceService} over a real {@link SqliteMemoryStore} (temp DB)
 * and a {@link FakeModelGateway}: orphan adoption weaves edgeless memories into the graph, stamps
 * them so a re-run is a no-op, resumes after cancellation, and fails closed (stamps nothing) when the
 * model provider is unreachable.
 */
class ReminiscenceServiceTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteMemoryStore store;
  private FakeModelGateway model;
  private ReminiscenceService service;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-reminisce-test-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(dataSource).load().migrate();

    store = new SqliteMemoryStore(JdbcClient.create(dataSource));
    model = new FakeModelGateway();
    service = new ReminiscenceService(store, model, new ReminiscenceProperties(8, 6000, 500));
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

  /** Seed N edgeless FACT memories; the fake yields a 2-entity/1-edge fragment for normal content. */
  private Profile seed(String profileName, String... contents) {
    Profile p = store.getOrCreateProfile(profileName);
    int s = 0;
    for (String content : contents) {
      store.store(p.id(), Memory.of(MemoryType.FACT, content, "s" + s++, null, null));
    }
    return p;
  }

  @Test
  void adoptsOrphansAndASecondRunIsANoop() {
    Profile p = seed("rem-adopt", "alpha beta one", "delta epsilon two", "eta theta three", "NOGRAPH trivial");

    ReminiscenceResult result = service.adoptOrphans("rem-adopt", IngestProgressListener.noop());

    assertEquals(4, result.memoriesScanned());
    assertEquals(3, result.memoriesAdopted());   // the NOGRAPH one yields nothing
    assertEquals(6, result.entitiesAdded());      // 2 per adopted memory
    assertEquals(3, result.edgesAdded());         // 1 per adopted memory
    assertFalse(store.graphSnapshot(p.id()).links().isEmpty());
    assertEquals(0L, store.countGraphOrphans(p.id()), "every scanned memory is stamped");

    // Re-run: nothing left to adopt.
    ReminiscenceResult again = service.adoptOrphans("rem-adopt", IngestProgressListener.noop());
    assertEquals(0, again.memoriesScanned());
    assertEquals(0, again.memoriesAdopted());
  }

  @Test
  void cancellationPropagatesAndKeepsAlreadyAdoptedRowsStamped() {
    Profile p = seed("rem-cancel", "alpha beta one", "delta epsilon two", "eta theta three");
    // One memory per model call so the first commits before the cancel tick fires.
    ReminiscenceService oneAtATime =
      new ReminiscenceService(store, model, new ReminiscenceProperties(1, 6000, 500));

    // The progress listener is the cancellation checkpoint: throw once at least one memory is done.
    IngestProgressListener cancelAfterFirst = (phase, done, total) -> {
      if (done >= 1) {
        throw new TaskCancelledException();
      }
    };

    assertThrows(TaskCancelledException.class,
      () -> oneAtATime.adoptOrphans("rem-cancel", cancelAfterFirst));

    long remaining = store.countGraphOrphans(p.id());
    assertTrue(remaining < 3, "at least one memory was adopted and stamped before cancellation");
    assertTrue(remaining >= 1, "cancellation stopped the run before adopting everything");
  }

  @Test
  void modelProviderUnreachableFailsClosedWithoutStampingAnything() {
    Profile p = seed("rem-down", "alpha beta one", "delta epsilon two");
    model.setUnavailable(true);

    assertThrows(ModelUnavailableException.class,
      () -> service.adoptOrphans("rem-down", IngestProgressListener.noop()));

    // Nothing was stamped: every seeded memory is still an orphan, retryable once the model returns.
    assertEquals(2L, store.countGraphOrphans(p.id()));
  }

  @Test
  void automaticEnrichmentOnlyAdoptsOnboardingSessionsWhileManualSweepStillAdoptsAll() {
    Profile p = store.getOrCreateProfile("rem-scoped");
    store.store(p.id(), Memory.of(MemoryType.FACT, "onboard alpha beta",
      ContentIngestor.SESSION_ID, null, null));
    store.store(p.id(), Memory.of(MemoryType.FACT, "manual gamma delta",
      "human-session", null, null));

    ReminiscenceResult automatic = service.adoptOnboardingOrphans(
      "rem-scoped", IngestProgressListener.noop());

    assertEquals(1, automatic.memoriesScanned());
    assertEquals(1L, store.countGraphOrphans(p.id()), "manual-session orphan must remain");
    ReminiscenceResult manual = service.adoptOrphans("rem-scoped", IngestProgressListener.noop());
    assertEquals(1, manual.memoriesScanned());
    assertEquals(0L, store.countGraphOrphans(p.id()));
  }
}
