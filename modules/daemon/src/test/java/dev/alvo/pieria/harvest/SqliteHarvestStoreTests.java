package dev.alvo.pieria.harvest;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.storage.SqliteMemoryStore;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteHarvestStoreTests {

  private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

  private Path dbFile;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;
  private SqliteMemoryStore memories;
  private SqliteHarvestStore store;
  private String profileId;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-harvest-store-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = JdbcClient.create(dataSource);
    memories = new SqliteMemoryStore(jdbc);
    store = new SqliteHarvestStore(jdbc);
    profileId = memories.getOrCreateProfile("harvest").id();
  }

  @AfterEach
  void tearDown() throws Exception {
    dataSource.close();
    Files.deleteIfExists(dbFile);
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-wal"));
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-shm"));
  }

  private HarvestSource source(String id, SourceStatus status, Instant nextRunAt) {
    return new HarvestSource(id, profileId, "jira", "{\"a\":1}", SourceMode.FULL, 3600, 10, 5000L, status, null, nextRunAt, 0, null, T0);
  }

  private HarvestSource created(String id) {
    HarvestSource source = source(id, SourceStatus.ACTIVE, T0);
    store.createSource(source);
    return source;
  }

  private static HarvestItem item(String source, String id) {
    return new HarvestItem(source, id, "session-" + id, "v1", null, 0, null);
  }

  @Test
  void createsAndFindsASource() {
    HarvestSource source = created("jira-acme");

    assertThat(store.findSource("jira-acme")).contains(source);
    assertThat(store.findSource("missing")).isEmpty();
    assertThat(store.listSources(profileId)).containsExactly(source);
  }

  @Test
  void dueSourcesAreActiveAndPastTheirTime() {
    store.createSource(source("due", SourceStatus.ACTIVE, T0));
    store.createSource(source("paused", SourceStatus.PAUSED, T0));
    store.createSource(source("auth", SourceStatus.NEEDS_AUTH, T0));
    store.createSource(source("later", SourceStatus.ACTIVE, T0.plusSeconds(100)));

    assertThat(store.dueSources(T0.plusSeconds(1), 10)).extracting(HarvestSource::id).containsExactly("due");
  }

  @Test
  void dueOrderingHandlesFractionalSeconds() {
    store.createSource(source("whole", SourceStatus.ACTIVE, T0));
    store.createSource(source("fraction", SourceStatus.ACTIVE, T0.plusMillis(500)));

    assertThat(store.dueSources(T0.plusMillis(100), 10)).extracting(HarvestSource::id).containsExactly("whole");
  }

  @Test
  void savedCursorIsReturned() {
    created("s");

    store.saveCursor("s", "{\"next\":3}");

    assertThat(store.findSource("s").orElseThrow().cursorJson()).isEqualTo("{\"next\":3}");
  }

  @Test
  void schedulingAndStatusUpdate() {
    created("s");

    store.scheduleNext("s", T0.plusSeconds(60), 2);
    store.setStatus("s", SourceStatus.NEEDS_AUTH);
    store.setSweepBlocked("s", 4L);

    HarvestSource updated = store.findSource("s").orElseThrow();
    assertThat(updated.nextRunAt()).isEqualTo(T0.plusSeconds(60));
    assertThat(updated.consecutiveFailures()).isEqualTo(2);
    assertThat(updated.status()).isEqualTo(SourceStatus.NEEDS_AUTH);
    assertThat(updated.sweepBlockedCount()).isEqualTo(4L);

    store.setSweepBlocked("s", null);
    assertThat(store.findSource("s").orElseThrow().sweepBlockedCount()).isNull();
  }

  @Test
  void finishRunRecordsCounts() {
    created("s");
    String runId = store.startRun("s", true);
    assertThat(store.listRuns("s", 5).getFirst().status()).isEqualTo(RunStatus.RUNNING);

    store.finishRun(new HarvestRun(runId, "s", true, RunStatus.FAILED, null, T0, 7, 3, 1, 9, 100, "auth", "denied", "tail"));

    HarvestRun run = store.listRuns("s", 5).getFirst();
    assertThat(run.dryRun()).isTrue();
    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
    assertThat(run.startedAt()).isNotNull();
    assertThat(run.finishedAt()).isEqualTo(T0);
    assertThat(List.of(run.itemsSeen(), run.itemsChanged(), run.itemsDeleted(), run.memoriesStored(), run.chars()))
      .containsExactly(7L, 3L, 1L, 9L, 100L);
    assertThat(run.errorClass()).isEqualTo("auth");
    assertThat(run.errorMessage()).isEqualTo("denied");
    assertThat(run.stderrTail()).isEqualTo("tail");
  }

  @Test
  void interruptRunningRunsMarksThemAndMakesSourcesDue() {
    store.createSource(source("s", SourceStatus.ACTIVE, T0.plusSeconds(9999)));
    store.startRun("s", false);

    int interrupted = store.interruptRunningRuns(T0);

    assertThat(interrupted).isEqualTo(1);
    assertThat(store.listRuns("s", 5).getFirst().status()).isEqualTo(RunStatus.INTERRUPTED);
    assertThat(store.findSource("s").orElseThrow().nextRunAt()).isEqualTo(T0);
  }

  @Test
  void upsertItemReplaces() {
    created("s");
    store.upsertItem(item("s", "a"));

    store.upsertItem(new HarvestItem("s", "a", "session-a", "v2", "prefix", 3, "run-1"));

    assertThat(store.findItem("s", "a")).contains(new HarvestItem("s", "a", "session-a", "v2", "prefix", 3, "run-1"));
  }

  @Test
  void markSeenAndUnseenItems() {
    created("s");
    List.of("a", "b", "c").forEach(id -> store.upsertItem(item("s", id)));

    store.markSeen("s", List.of("a", "b"), "run-1");

    assertThat(store.unseenItems("s", "run-1")).extracting(HarvestItem::externalId).containsExactly("c");
    assertThat(store.liveItemCount("s")).isEqualTo(3);
  }

  @Test
  void deleteItemRemovesItAndItsLinks() {
    created("s");
    store.upsertItem(item("s", "a"));
    store.addLinks("s", "a", List.of(new MemoryLink("m", "as", "r")));

    store.deleteItem("s", "a");

    assertThat(store.findItem("s", "a")).isEmpty();
    assertThat(store.links("s", "a")).isEmpty();
  }

  @Test
  void linksRoundTripAndRemove() {
    created("s");
    MemoryLink first = new MemoryLink("m1", "a1", "r1");
    MemoryLink second = new MemoryLink("m2", "a2", "r2");

    store.addLinks("s", "item", List.of(first, second));
    assertThat(store.links("s", "item")).containsExactlyInAnyOrder(first, second);

    store.addLinks("s", "item", List.of(new MemoryLink("m1", "a1", "r1b")));
    assertThat(store.links("s", "item")).as("a link keeps the revision it was made from")
      .contains(new MemoryLink("m1", "a1", "r1")).hasSize(2);

    store.removeLinks("s", "item", List.of("m1"));
    assertThat(store.links("s", "item")).containsExactly(second);
  }

  private long rows(String table) {
    return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
  }

  private void populate() {
    created("s");
    store.upsertItem(item("s", "a"));
    store.addLinks("s", "a", List.of(new MemoryLink("m", "as", "r")));
    store.startRun("s", false);
  }

  @Test
  void deleteSourceRemovesItsRunsItemsAndLinks() {
    populate();

    store.deleteSource("s");

    assertThat(List.of(rows("harvest_sources"), rows("harvest_runs"), rows("harvest_items"), rows("harvest_item_memories")))
      .containsOnly(0L);
  }

  @Test
  void deleteProfileRemovesHarvestRows() {
    populate();

    memories.deleteProfile(profileId);

    assertThat(List.of(rows("harvest_sources"), rows("harvest_runs"), rows("harvest_items"), rows("harvest_item_memories")))
      .containsOnly(0L);
  }

  @Test
  void deleteSessionMessagesRemovesThemFromFullTextSearch() {
    memories.insertMessages(profileId, "s1", List.of(new Message(null, "s1", "user", "oolong detail", null)));
    memories.insertMessages(profileId, "s2", List.of(new Message(null, "s2", "user", "oolong elsewhere", null)));
    Memory first = memories.insertMemory(profileId, new Memory(null, "s1", MemoryType.FACT, "first", null, null, false, "{}", null, T0));
    Memory second = memories.insertMemory(profileId, new Memory(null, "s2", MemoryType.FACT, "second", null, null, false, "{}", null, T0));

    memories.deleteSessionMessages(profileId, "s1");

    assertThat(memories.searchMemoriesByMessageFts(profileId, "oolong", 5))
      .extracting(Memory::id).contains(second.id()).doesNotContain(first.id());
  }

  @Test
  void clearChunkLedgerAffectsOnlyThatSession() {
    memories.recordIngestLedger(profileId, "chunk", Map.of("s_1#0", "h1", "sx1#0", "h2"));

    memories.clearChunkLedger(profileId, "s_1");

    assertThat(memories.ingestLedger(profileId, "chunk")).containsOnlyKeys("sx1#0");
  }
}
