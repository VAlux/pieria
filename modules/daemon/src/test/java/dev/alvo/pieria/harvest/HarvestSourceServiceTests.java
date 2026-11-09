package dev.alvo.pieria.harvest;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.error.ConflictException;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HarvestSourceServiceTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String MANIFEST = """
    id = "jira"
    name = "Jira"
    version = "1"
    protocol = 1
    command = ["./run"]

    [[config]]
    key = "baseUrl"
    kind = "string"
    label = "URL"
    required = true

    [[config]]
    key = "token"
    kind = "secret"
    label = "Token"
    required = true
    """;

  @TempDir
  Path temp;

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteMemoryStore memories;
  private SqliteHarvestStore store;
  private HarvestSecretStore secrets;
  private HarvestItemIngestor ingestor;
  private HarvestScheduler scheduler;
  private HarvestRunService runs;
  private HarvestSourceService service;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-source-service-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    Flyway.configure().dataSource(dataSource).load().migrate();
    JdbcClient jdbc = JdbcClient.create(dataSource);
    memories = new SqliteMemoryStore(jdbc);
    store = new SqliteHarvestStore(jdbc);
    HarvestPaths paths = new HarvestPaths(temp.resolve("harvesters"));
    Path pkg = Files.createDirectories(temp.resolve("pkg"));
    Files.writeString(pkg.resolve("harvester.toml"), MANIFEST);
    Files.writeString(pkg.resolve("run"), "x");
    new HarvesterInstaller(paths, store).install(pkg, null, null, false);
    secrets = new HarvestSecretStore(paths);
    ingestor = mock(HarvestItemIngestor.class);
    scheduler = mock(HarvestScheduler.class);
    runs = mock(HarvestRunService.class);
    when(scheduler.stopAndWait(any(), any())).thenReturn(true);
    service = service("http://localhost:11434");
  }

  private HarvestSourceService service(String baseUrl) {
    PieriaProperties properties = new PieriaProperties(null, null,
      new PieriaProperties.Provider(baseUrl, "key", "name", "openai", "v"), null, null, null, null);
    return new HarvestSourceService(store, memories, new HarvesterRegistry(new HarvestPaths(temp.resolve("harvesters"))), secrets,
      ingestor, scheduler, runs, properties);
  }

  @AfterEach
  void tearDown() throws Exception {
    dataSource.close();
    Files.deleteIfExists(dbFile);
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-wal"));
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-shm"));
  }

  private static ObjectNode config() {
    return MAPPER.createObjectNode().put("baseUrl", "https://x");
  }

  private static SourceDraft draft(String id, boolean start) {
    return new SourceDraft(id, "jira", config(), Map.of("token", "hunter2"), SourceMode.FULL, 3600, null, null, start);
  }

  private HarvestSource created(String id) {
    return service.create("p", draft(id, false));
  }

  private String pid() {
    return memories.getOrCreateProfile("p").id();
  }

  private long sourceRows() {
    return store.listSources(pid()).size();
  }

  @Test
  void createsPausedByDefault() {
    assertThat(created("s").status()).isEqualTo(SourceStatus.PAUSED);
  }

  @Test
  void startTrueIsActiveAndDue() {
    HarvestSource source = service.create("p", draft("s", true));

    assertThat(source.status()).isEqualTo(SourceStatus.ACTIVE);
    assertThat(store.dueSources(Instant.now().plusSeconds(1), 5)).extracting(HarvestSource::id).containsExactly("s");
  }

  @Test
  void storesSecretsSeparatelyFromConfig() {
    HarvestSource source = created("s");

    assertThat(source.configJson()).doesNotContain("hunter2");
    assertThat(secrets.secrets("s")).containsEntry("token", "hunter2");
  }

  @Test
  void invalidConfigWritesNothing() {
    SourceDraft bad = new SourceDraft("s", "jira", config().put("nope", 1), Map.of("token", "hunter2"), SourceMode.FULL, 3600, null, null, false);

    assertThatThrownBy(() -> service.create("p", bad)).isInstanceOf(IllegalArgumentException.class);

    assertThat(sourceRows()).isZero();
    assertThat(secrets.secrets("s")).isEmpty();
  }

  @Test
  void duplicateIdConflicts() {
    created("s");

    assertThatThrownBy(() -> created("s")).isInstanceOf(ConflictException.class);
  }

  @Test
  void duplicateIdAcrossProfilesConflicts() {
    created("s");

    assertThatThrownBy(() -> service.create("other", draft("s", false))).isInstanceOf(ConflictException.class);
  }

  @Test
  void invalidIdIsRejected() {
    assertThatThrownBy(() -> created("Bad Id")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unknownHarvesterIsRejected() {
    SourceDraft bad = new SourceDraft("s", "absent", config(), Map.of(), SourceMode.FULL, 3600, null, null, false);

    assertThatThrownBy(() -> service.create("p", bad)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void intervalBelowTheMinimumIsRejected() {
    SourceDraft bad = new SourceDraft("s", "jira", config(), Map.of("token", "t"), SourceMode.FULL, 59, null, null, false);

    assertThatThrownBy(() -> service.create("p", bad)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonPositiveLimitsAreRejected() {
    SourceDraft bad = new SourceDraft("s", "jira", config(), Map.of("token", "t"), SourceMode.FULL, 3600, 0, null, false);

    assertThatThrownBy(() -> service.create("p", bad)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anotherProfilesSourceIsNotFound() {
    created("s");
    memories.getOrCreateProfile("other");

    assertThatThrownBy(() -> service.get("other", "s")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void unknownProfileIsNotFound() {
    assertThatThrownBy(() -> service.list("absent")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void updateMergesConfig() {
    created("s");

    HarvestSource updated = service.update("p", "s", new SourceUpdate(MAPPER.createObjectNode().put("baseUrl", "https://y"), SourceMode.RAW, 120L, 5, 9L, false));

    assertThat(updated.configJson()).contains("https://y");
    assertThat(updated.mode()).isEqualTo(SourceMode.RAW);
    assertThat(updated.intervalSeconds()).isEqualTo(120);
    assertThat(updated.maxItemsPerRun()).isEqualTo(5);
    assertThat(updated.maxCharsPerRun()).isEqualTo(9L);
  }

  @Test
  void updateKeepsWhatIsNotGiven() {
    created("s");
    store.saveCursor("s", "{\"n\":1}");

    HarvestSource updated = service.update("p", "s", new SourceUpdate(null, null, 120L, null, null, false));

    assertThat(updated.configJson()).contains("https://x");
    assertThat(updated.mode()).isEqualTo(SourceMode.FULL);
    assertThat(updated.cursorJson()).isEqualTo("{\"n\":1}");
  }

  @Test
  void updateCanResetTheCursor() {
    created("s");
    store.saveCursor("s", "{\"n\":1}");

    assertThat(service.update("p", "s", new SourceUpdate(null, null, null, null, null, true)).cursorJson()).isNull();
  }

  @Test
  void invalidUpdateChangesNothing() {
    created("s");

    assertThatThrownBy(() -> service.update("p", "s", new SourceUpdate(MAPPER.createObjectNode().put("nope", 1), null, null, null, null, false)))
      .isInstanceOf(IllegalArgumentException.class);

    assertThat(store.findSource("s").orElseThrow().configJson()).doesNotContain("nope");
  }

  @Test
  void updateRefusedWhileRunning() {
    created("s");
    when(scheduler.isRunning("s")).thenReturn(true);

    assertThatThrownBy(() -> service.update("p", "s", new SourceUpdate(null, null, 120L, null, null, false)))
      .isInstanceOf(ConflictException.class);
  }

  @Test
  void removeStopsTheRunFirst() {
    created("s");
    AtomicBoolean presentWhenStopping = new AtomicBoolean();
    when(scheduler.stopAndWait(eq("s"), any())).thenAnswer(invocation -> {
      presentWhenStopping.set(store.findSource("s").isPresent());
      return true;
    });

    service.remove("p", "s", false);

    assertThat(presentWhenStopping).isTrue();
    assertThat(store.findSource("s")).isEmpty();
  }

  @Test
  void removeConflictsWhenARunWillNotStop() {
    created("s");
    when(scheduler.stopAndWait(eq("s"), any())).thenReturn(false);

    assertThatThrownBy(() -> service.remove("p", "s", false)).isInstanceOf(ConflictException.class);

    assertThat(store.findSource("s")).isPresent();
    assertThat(secrets.secrets("s")).isNotEmpty();
  }

  private void item(String id) {
    store.upsertItem(new HarvestItem("s", id, "session-" + id, "v", null, 0, null));
  }

  @Test
  void removeKeepsMemoriesByDefault() {
    HarvestSource source = created("s");
    item("a");

    service.remove("p", "s", false);

    verify(ingestor, never()).delete(eq(source), any());
  }

  @Test
  void removeCanForgetMemories() {
    created("s");
    item("a");
    item("b");

    service.remove("p", "s", true);

    verify(ingestor).delete(any(), eq("a"));
    verify(ingestor).delete(any(), eq("b"));
    assertThat(store.findSource("s")).isEmpty();
  }

  @Test
  void removeDeletesSecrets() {
    created("s");

    service.remove("p", "s", false);

    assertThat(secrets.secrets("s")).isEmpty();
  }

  @Test
  void pauseStopsScheduling() {
    service.create("p", draft("s", true));

    service.pause("p", "s");

    assertThat(store.findSource("s").orElseThrow().status()).isEqualTo(SourceStatus.PAUSED);
  }

  @Test
  void resumeClearsNeedsAuthAndMakesDue() {
    created("s");
    store.setStatus("s", SourceStatus.NEEDS_AUTH);
    store.scheduleNext("s", Instant.now().plusSeconds(9999), 3);

    service.resume("p", "s");

    HarvestSource source = store.findSource("s").orElseThrow();
    assertThat(source.status()).isEqualTo(SourceStatus.ACTIVE);
    assertThat(source.consecutiveFailures()).isZero();
    assertThat(store.dueSources(Instant.now().plusSeconds(1), 5)).extracting(HarvestSource::id).containsExactly("s");
  }

  @Test
  void pausedSourceCanStillBeRun() {
    created("s");
    UUID task = UUID.randomUUID();
    when(scheduler.launch("s", true)).thenReturn(Optional.of(task));

    assertThat(service.run("p", "s", true)).isEqualTo(task);
  }

  @Test
  void busySourceRunConflicts() {
    created("s");
    when(scheduler.launch("s", false)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.run("p", "s", false)).isInstanceOf(ConflictException.class);
  }

  @Test
  void acceptSweepSetsTheFlag() {
    created("s");

    service.acceptNextSweep("p", "s");

    assertThat(store.acceptNextSweep("s")).isTrue();
  }

  @Test
  void checkDelegates() {
    created("s");
    when(runs.check("s")).thenReturn(SourceCheckResult.passed());

    assertThat(service.check("p", "s").ok()).isTrue();
  }

  @Test
  void setSecretsReplacesAValue() {
    created("s");

    service.setSecrets("p", "s", Map.of("token", "new"));

    assertThat(secrets.secrets("s")).containsEntry("token", "new");
  }

  @Test
  void setSecretsRejectsUndeclaredNames() {
    created("s");

    assertThatThrownBy(() -> service.setSecrets("p", "s", Map.of("other", "x"))).isInstanceOf(IllegalArgumentException.class);

    assertThat(secrets.secrets("s")).doesNotContainKey("other");
  }

  @Test
  void secretNamesNeverIncludeValues() {
    created("s");

    assertThat(service.secretNames("s")).containsExactly("token");
  }

  @Test
  void loopbackProviderIsLocal() {
    assertThat(service("http://localhost:11434").usesRemoteModelProvider()).isFalse();
    assertThat(service("http://127.0.0.1:1234").usesRemoteModelProvider()).isFalse();
    assertThat(service("http://[::1]:1234").usesRemoteModelProvider()).isFalse();
  }

  @Test
  void remoteProviderIsReported() {
    assertThat(service("https://api.openai.com").usesRemoteModelProvider()).isTrue();
  }

  @Test
  void removeAllForStopsAndClearsSecrets() {
    created("s");

    service.removeAllFor(pid());

    verify(scheduler).stopAndWait(eq("s"), any());
    assertThat(secrets.secrets("s")).isEmpty();
  }
}
