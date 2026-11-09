package dev.alvo.pieria.harvest;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.config.HarvestProperties;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.reminiscence.ReminiscenceService;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import dev.alvo.pieria.task.TaskProgress;
import dev.alvo.pieria.task.TaskRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HarvestSchedulerTests {

  private static final Instant PAST = Instant.parse("2026-03-01T00:00:00Z");

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteHarvestStore store;
  private HarvestRunService runs;
  private ReminiscenceService reminiscence;
  private TaskRegistry tasks;
  private String profileId;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-harvest-scheduler-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    Flyway.configure().dataSource(dataSource).load().migrate();
    JdbcClient jdbc = JdbcClient.create(dataSource);
    store = new SqliteHarvestStore(jdbc);
    profileId = new SqliteMemoryStore(jdbc).getOrCreateProfile("p").id();
    runs = mock(HarvestRunService.class);
    reminiscence = mock(ReminiscenceService.class);
    tasks = new TaskRegistry();
  }

  @AfterEach
  void tearDown() throws Exception {
    tasks.shutdown();
    dataSource.close();
    Files.deleteIfExists(dbFile);
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-wal"));
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-shm"));
  }

  private HarvestScheduler scheduler(boolean enabled, int maxRuns) {
    return new HarvestScheduler(store, runs, tasks,
      new HarvestProperties(enabled, 30_000, maxRuns, 1, Duration.ofHours(6), 0.2, 2, Duration.ofHours(24), ""), reminiscence);
  }

  private void source(String id, int failures, String cursor) {
    store.createSource(new HarvestSource(id, profileId, "jira", "{}", SourceMode.FULL, 3600, null, null, SourceStatus.ACTIVE, cursor,
      PAST, failures, null, PAST));
  }

  private static HarvestRunSummary summary(RunStatus status, ErrorClass errorClass, long memories, List<String> sessions) {
    return new HarvestRunSummary("r", status, 1, 1, 0, memories, 10, 1, false, null, errorClass, null, null, sessions);
  }

  private static HarvestRunSummary success() {
    return summary(RunStatus.SUCCEEDED, null, 0, List.of());
  }

  private void returns(HarvestRunSummary summary) {
    when(runs.run(any(), anyBoolean(), any(TaskProgress.class))).thenReturn(summary);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
      Thread.sleep(25);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private HarvestSource find(String id) {
    return store.findSource(id).orElseThrow();
  }

  private void awaitRescheduled(String id) throws InterruptedException {
    await(() -> find(id).nextRunAt().isAfter(PAST));
  }

  @Test
  void launchesDueSources() throws Exception {
    source("s", 0, null);
    returns(success());

    scheduler(true, 1).tick();

    verify(runs, timeout(5_000)).run(eq("s"), eq(false), any(TaskProgress.class));
    awaitRescheduled("s");
  }

  @Test
  void disabledDoesNothing() {
    source("s", 0, null);

    scheduler(false, 1).tick();

    verify(runs, never()).run(any(), anyBoolean(), any(TaskProgress.class));
  }

  private CountDownLatch blockRuns(CountDownLatch started) {
    CountDownLatch release = new CountDownLatch(1);
    when(runs.run(any(), anyBoolean(), any(TaskProgress.class))).thenAnswer(invocation -> {
      started.countDown();
      release.await(10, TimeUnit.SECONDS);
      return success();
    });
    return release;
  }

  @Test
  void respectsTheConcurrencyCap() throws Exception {
    source("a", 0, null);
    source("b", 0, null);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = blockRuns(started);
    HarvestScheduler scheduler = scheduler(true, 1);

    scheduler.tick();
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    scheduler.tick();

    Thread.sleep(200);
    verify(runs, times(1)).run(any(), anyBoolean(), any(TaskProgress.class));
    release.countDown();
  }

  @Test
  void doesNotRunTheSameSourceTwice() throws Exception {
    source("a", 0, null);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = blockRuns(started);
    HarvestScheduler scheduler = scheduler(true, 5);

    assertThat(scheduler.launch("a", false)).isPresent();
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(scheduler.launch("a", false)).isEmpty();
    release.countDown();
  }

  @Test
  void isRunningReflectsAnInFlightRun() throws Exception {
    source("a", 0, null);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = blockRuns(started);
    HarvestScheduler scheduler = scheduler(true, 1);
    assertThat(scheduler.isRunning("a")).isFalse();

    scheduler.launch("a", false);
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(scheduler.isRunning("a")).isTrue();
    release.countDown();
    await(() -> !scheduler.isRunning("a"));
  }

  @Test
  void stopAndWaitCancelsARunningSource() throws Exception {
    source("a", 0, null);
    CountDownLatch started = new CountDownLatch(1);
    when(runs.run(any(), anyBoolean(), any(TaskProgress.class))).thenAnswer(invocation -> {
      started.countDown();
      Thread.sleep(30_000);
      return success();
    });
    HarvestScheduler scheduler = scheduler(true, 1);
    scheduler.launch("a", false);
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(scheduler.stopAndWait("a", Duration.ofSeconds(5))).isTrue();

    assertThat(scheduler.isRunning("a")).isFalse();
  }

  @Test
  void stopAndWaitIsTrueWhenIdle() {
    assertThat(scheduler(true, 1).stopAndWait("a", Duration.ofSeconds(1))).isTrue();
  }

  @Test
  void stopAndWaitIsFalseWhenTheRunWillNotEnd() throws Exception {
    source("a", 0, null);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(runs.run(any(), anyBoolean(), any(TaskProgress.class))).thenAnswer(invocation -> {
      started.countDown();
      while (release.getCount() > 0) {
        try {
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          // This run ignores cancellation.
        }
      }
      return success();
    });
    HarvestScheduler scheduler = scheduler(true, 1);
    scheduler.launch("a", false);
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(scheduler.stopAndWait("a", Duration.ofMillis(200))).isFalse();
    release.countDown();
  }

  @Test
  void unknownSourceIsNotLaunched() {
    assertThat(scheduler(true, 1).launch("absent", false)).isEmpty();
  }

  @Test
  void successResetsFailures() throws Exception {
    source("s", 4, null);
    returns(success());

    scheduler(true, 1).tick();

    awaitRescheduled("s");
    assertThat(find("s").consecutiveFailures()).isZero();
  }

  @Test
  void failureIncrementsFailuresAndBacksOff() throws Exception {
    source("s", 1, null);
    returns(summary(RunStatus.FAILED, ErrorClass.TRANSIENT, 0, List.of()));
    Instant before = Instant.now();

    scheduler(true, 1).tick();

    awaitRescheduled("s");
    assertThat(find("s").consecutiveFailures()).isEqualTo(2);
    assertThat(find("s").nextRunAt()).isAfterOrEqualTo(before.plusSeconds(4 * 3600));
  }

  @Test
  void aCrashingRunStillReleasesCapacityAndReschedules() throws Exception {
    source("s", 0, null);
    source("t", 0, null);
    when(runs.run(eq("s"), anyBoolean(), any(TaskProgress.class))).thenThrow(new IllegalStateException("boom"));
    when(runs.run(eq("t"), anyBoolean(), any(TaskProgress.class))).thenReturn(success());
    HarvestScheduler scheduler = scheduler(true, 1);

    scheduler.tick();
    awaitRescheduled("s");
    assertThat(find("s").consecutiveFailures()).isEqualTo(1);

    await(() -> {
      scheduler.tick();
      return find("t").nextRunAt().isAfter(PAST);
    });
  }

  @Test
  void dryRunLeavesTheScheduleAlone() throws Exception {
    source("s", 2, null);
    CountDownLatch finished = new CountDownLatch(1);
    when(runs.run(any(), anyBoolean(), any(TaskProgress.class))).thenAnswer(invocation -> {
      finished.countDown();
      return success();
    });

    assertThat(scheduler(true, 1).launch("s", true)).isPresent();

    assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(200);
    assertThat(find("s").nextRunAt()).isEqualTo(PAST);
    assertThat(find("s").consecutiveFailures()).isEqualTo(2);
  }

  @Test
  void recoverMarksInterruptedRunsAndMakesThemDue() {
    store.createSource(new HarvestSource("s", profileId, "jira", "{}", SourceMode.FULL, 3600, null, null, SourceStatus.ACTIVE, null,
      Instant.now().plusSeconds(9999), 0, null, PAST));
    store.startRun("s", false);

    scheduler(true, 1).recover();

    assertThat(store.listRuns("s", 1).getFirst().status()).isEqualTo(RunStatus.INTERRUPTED);
    assertThat(store.dueSources(Instant.now().plusSeconds(1), 5)).extracting(HarvestSource::id).containsExactly("s");
  }

  @Test
  void aRetriedTaskCannotRunASourceThatIsAlreadyRunning() throws Exception {
    source("s", 0, null);
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(runs.run(any(), anyBoolean(), any(TaskProgress.class))).thenAnswer(invocation -> {
      if (calls.incrementAndGet() == 1) {
        throw new IllegalStateException("boom");
      }
      started.countDown();
      release.await(10, TimeUnit.SECONDS);
      return success();
    });
    HarvestScheduler scheduler = scheduler(true, 5);
    java.util.UUID crashed = scheduler.launch("s", false).orElseThrow();
    await(() -> tasks.find(crashed).map(task -> task.status() == dev.alvo.pieria.task.TaskStatus.FAILED).orElse(false));
    assertThat(scheduler.launch("s", false)).isPresent();
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

    tasks.retry(crashed);
    Thread.sleep(300);

    assertThat(calls.get()).isEqualTo(2);
    release.countDown();
  }

  @Test
  void aShutdownMakesTheSourceDueImmediately() throws Exception {
    source("s", 2, null);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(runs.run(any(), anyBoolean(), any(TaskProgress.class))).thenAnswer(invocation -> {
      started.countDown();
      release.await(10, TimeUnit.SECONDS);
      throw new dev.alvo.pieria.task.TaskCancelledException();
    });
    HarvestScheduler scheduler = scheduler(true, 1);
    assertThat(scheduler.launch("s", false)).isPresent();
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    Instant before = Instant.now();

    scheduler.shutdown();
    release.countDown();

    awaitRescheduled("s");
    verify(runs).beginShutdown();
    assertThat(find("s").nextRunAt()).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    assertThat(find("s").consecutiveFailures()).isEqualTo(2);
  }

  @Test
  void successfulRunSubmitsGraphAdoption() throws Exception {
    source("s", 0, null);
    returns(summary(RunStatus.SUCCEEDED, null, 3, List.of("session-1")));

    scheduler(true, 1).tick();

    verify(reminiscence, timeout(5_000)).adoptSessionOrphans(eq("p"), eq(List.of("session-1")), any(IngestProgressListener.class));
  }

  @Test
  void emptyRunSkipsGraphAdoption() throws Exception {
    source("s", 0, null);
    returns(summary(RunStatus.SUCCEEDED, null, 0, List.of()));

    scheduler(true, 1).tick();

    awaitRescheduled("s");
    Thread.sleep(200);
    verify(reminiscence, never()).adoptSessionOrphans(any(), anyList(), any(IngestProgressListener.class));
  }

  @Test
  void aFailedRunStillAdoptsTheSessionsItWrote() throws Exception {
    source("s", 0, null);
    returns(summary(RunStatus.FAILED, ErrorClass.FATAL, 3, List.of("session-1")));

    scheduler(true, 1).tick();

    verify(reminiscence, timeout(5_000)).adoptSessionOrphans(eq("p"), eq(List.of("session-1")), any(IngestProgressListener.class));
  }
}
