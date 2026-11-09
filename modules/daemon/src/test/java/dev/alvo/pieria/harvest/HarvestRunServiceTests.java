package dev.alvo.pieria.harvest;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.HarvestProperties;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.VerifyMode;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.ingestion.Chunker;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.ingestion.TranscriptNormalizer;
import dev.alvo.pieria.ingestion.model.Chunk;
import dev.alvo.pieria.ingestion.model.UnifiedCandidate;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import dev.alvo.pieria.task.TaskCancelledException;
import dev.alvo.pieria.task.TaskRegistry;
import dev.alvo.pieria.tools.Hash;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisabledOnOs(OS.WINDOWS)
class HarvestRunServiceTests {

  private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TOKEN = "t0ken-value-for-tests";

  private static final class CountingGateway extends FakeModelGateway {
    final AtomicInteger extractions = new AtomicInteger();

    @Override
    public List<UnifiedCandidate> extractUnified(Chunk chunk) {
      extractions.incrementAndGet();
      return super.extractUnified(chunk);
    }
  }

  @TempDir
  Path root;

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteMemoryStore memories;
  private SqliteHarvestStore store;
  private CountingGateway model;
  private HarvestPaths paths;
  private HarvesterRegistry registry;
  private HarvestSecretStore secrets;
  private TaskRegistry tasks;
  private String profileId;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-harvest-run-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(dataSource).load().migrate();
    JdbcClient jdbc = JdbcClient.create(dataSource);
    memories = new SqliteMemoryStore(jdbc);
    store = new SqliteHarvestStore(jdbc);
    model = new CountingGateway();
    paths = new HarvestPaths(root);
    registry = new HarvesterRegistry(paths);
    secrets = new HarvestSecretStore(paths);
    tasks = new TaskRegistry();
    profileId = memories.getOrCreateProfile("p").id();
    install();
  }

  @AfterEach
  void tearDown() throws Exception {
    tasks.shutdown();
    dataSource.close();
    Files.deleteIfExists(dbFile);
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-wal"));
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-shm"));
  }

  private void install() throws Exception {
    Path dir = Files.createDirectories(root.resolve("scripted"));
    Path script = dir.resolve("run.sh");
    Files.writeString(script, "#!/bin/sh\nexec '" + ProcessHandle.current().info().command().orElseThrow() + "' -cp '"
      + System.getProperty("java.class.path") + "' " + ScriptedHarvester.class.getName() + " \"$@\"\n");
    assertThat(script.toFile().setExecutable(true)).isTrue();
    Files.writeString(dir.resolve("harvester.toml"), """
      id = "scripted"
      name = "Scripted"
      version = "1"
      protocol = 1
      command = ["./run.sh"]
      capabilities = ["incremental", "deletes"]

      [[config]]
      key = "token"
      kind = "secret"
      label = "Token"
      """);
    Files.writeString(paths.pinsFile(), "{\"scripted\":\"" + Hash.sha256Hex(script) + "\"}");
  }

  private HarvestRunService service(Duration timeout) {
    HarvestProperties properties = new HarvestProperties(true, 30_000, 1, 1, timeout, 0.2, 2, Duration.ofHours(24), "");
    PieriaProperties pieria = new PieriaProperties(null, null, null,
      new PieriaProperties.Model("small", "large", "embed", 1024, 4, null, null),
      new PieriaProperties.Ingestion(1000, 0, 4, VerifyMode.ALWAYS, 1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70),
      null, null);
    EffectiveConfigResolver resolver = EffectiveConfigResolver.withoutOverrides(pieria);
    TranscriptNormalizer normalizer = new TranscriptNormalizer();
    IngestionService ingestion = new IngestionService(memories, model, normalizer, new Chunker(normalizer), resolver);
    HarvestItemIngestor ingestor = new HarvestItemIngestor(store, memories, ingestion, properties);
    return new HarvestRunService(store, registry, secrets, ingestor, properties, resolver);
  }

  private static ObjectNode config() {
    return MAPPER.createObjectNode();
  }

  private static ObjectNode document(String id, String text) {
    return MAPPER.createObjectNode().put("type", "document").put("id", id).put("title", "T").put("url", "https://x/" + id)
      .put("updatedAt", T0.toString()).put("format", "text").put("text", text);
  }

  private static ObjectNode sweep() {
    return MAPPER.createObjectNode().put("type", "sweep");
  }

  private static ObjectNode checkpoint(int next) {
    ObjectNode record = MAPPER.createObjectNode().put("type", "checkpoint");
    record.putObject("cursor").put("next", next);
    return record;
  }

  private static ObjectNode error(String errorClass, String message) {
    return MAPPER.createObjectNode().put("type", "error").put("class", errorClass).put("message", message);
  }

  private static ObjectNode withRecords(ObjectNode... records) {
    ObjectNode config = config();
    ArrayNode array = config.putArray("records");
    for (ObjectNode record : records) {
      array.add(record);
    }
    return config;
  }

  private void createSource(String id, ObjectNode config, SourceMode mode) {
    store.createSource(new HarvestSource(id, profileId, "scripted", config.toString(), mode, 3600, null, null,
      SourceStatus.ACTIVE, null, T0, 0, null, T0));
  }

  /** Replaces the source's script, keeping its cursor and items. */
  private void reconfigure(String id, ObjectNode config) {
    JdbcClient.create(dataSource).sql("UPDATE harvest_sources SET config_json = ? WHERE id = ?").params(config.toString(), id).update();
  }

  private HarvestRunSummary run(String id, boolean dryRun, Duration timeout) throws Exception {
    CompletableFuture<HarvestRunSummary> result = new CompletableFuture<>();
    tasks.submit("harvest", "p", progress -> {
      try {
        result.complete(service(timeout).run(id, dryRun, progress));
      } catch (RuntimeException e) {
        result.completeExceptionally(e);
        throw e;
      }
      return MAPPER.createObjectNode();
    });
    return result.get(60, TimeUnit.SECONDS);
  }

  private HarvestRunSummary run(String id) throws Exception {
    return run(id, false, Duration.ofSeconds(60));
  }

  private HarvestRunSummary scripted(ObjectNode config) throws Exception {
    createSource("s", config, SourceMode.FULL);
    return run("s");
  }

  private HarvestSource source() {
    return store.findSource("s").orElseThrow();
  }

  private HarvestRun lastRun() {
    return store.listRuns("s", 1).getFirst();
  }

  private List<Memory> search(String word) {
    return memories.searchMemoriesFts(profileId, word, 10);
  }

  @Test
  void ingestsItemsAndSavesTheCursor() throws Exception {
    HarvestRunSummary summary = scripted(withRecords(document("a", "EVENT about zanzibar"), checkpoint(1)));

    assertThat(summary.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(summary.itemsSeen()).isEqualTo(1);
    assertThat(summary.itemsChanged()).isEqualTo(1);
    assertThat(summary.memoriesStored()).isEqualTo(1);
    assertThat(search("zanzibar")).hasSize(1);
    assertThat(source().cursorJson()).isEqualTo("{\"next\":1}");
    assertThat(lastRun().status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(lastRun().finishedAt()).isNotNull();
    assertThat(summary.sessions()).hasSize(1);
  }

  @Test
  void resumesFromTheSavedCursor() throws Exception {
    scripted(withRecords(checkpoint(5)));
    ObjectNode config = config().put("echoCursor", true);
    reconfigure("s", config);

    run("s");

    assertThat(search("next")).hasSize(1);
    assertThat(search("next").getFirst().content()).contains("next").contains("5");
  }

  @Test
  void onlyDeclaredSecretsAreSent() throws Exception {
    secrets.put("s", "token", TOKEN);
    secrets.put("s", "undeclared", "other");

    scripted(config().put("echoSecrets", true));

    assertThat(search("secrets").getFirst().content()).contains("token").doesNotContain("undeclared");
  }

  @Test
  void checkpointsFromAFailedRunStand() throws Exception {
    HarvestRunSummary summary = scripted(withRecords(document("a", "text"), checkpoint(1), error("transient", "later")));

    assertThat(summary.status()).isEqualTo(RunStatus.FAILED);
    assertThat(summary.errorClass()).isEqualTo(ErrorClass.TRANSIENT);
    assertThat(source().cursorJson()).isEqualTo("{\"next\":1}");
  }

  @Test
  void noCheckpointKeepsThePreviousCursor() throws Exception {
    scripted(withRecords(checkpoint(1)));
    reconfigure("s", withRecords(document("a", "text")));

    run("s");

    assertThat(source().cursorJson()).isEqualTo("{\"next\":1}");
  }

  private void seedItems(String... ids) throws Exception {
    ObjectNode[] records = new ObjectNode[ids.length];
    for (int i = 0; i < ids.length; i++) {
      records[i] = document(ids[i], "EVENT content of " + ids[i]);
    }
    createSource("s", withRecords(records), SourceMode.FULL);
    run("s");
  }

  private long live() {
    return store.liveItemCount("s");
  }

  @Test
  void sweepDeletesUnseenItems() throws Exception {
    seedItems("a", "b");
    reconfigure("s", withRecords(document("a", "EVENT content of a"), sweep()));

    HarvestRunSummary summary = run("s");

    assertThat(summary.sweepApplied()).isTrue();
    assertThat(summary.itemsDeleted()).isEqualTo(1);
    assertThat(live()).isEqualTo(1);
  }

  @Test
  void sweepIsIgnoredWhenTheRunFails() throws Exception {
    seedItems("a", "b");
    ObjectNode config = withRecords(sweep());
    config.put("exit", 1);
    reconfigure("s", config);

    HarvestRunSummary summary = run("s");

    assertThat(summary.status()).isEqualTo(RunStatus.FAILED);
    assertThat(summary.errorClass()).isEqualTo(ErrorClass.FATAL);
    assertThat(live()).isEqualTo(2);
  }

  @Test
  void sweepIsIgnoredWithExitZeroAndError() throws Exception {
    seedItems("a", "b");
    reconfigure("s", withRecords(sweep(), error("transient", "oops")));

    HarvestRunSummary summary = run("s");

    assertThat(summary.status()).isEqualTo(RunStatus.FAILED);
    assertThat(live()).isEqualTo(2);
  }

  @Test
  void seenProtectsAnItemFromSweep() throws Exception {
    seedItems("a", "b");
    ObjectNode seen = MAPPER.createObjectNode().put("type", "seen");
    seen.putArray("ids").add("a").add("b");
    reconfigure("s", withRecords(seen, sweep()));

    run("s");

    assertThat(live()).isEqualTo(2);
  }

  @Test
  void massDeleteIsBlockedAndFlagged() throws Exception {
    seedItems("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
    ObjectNode seen = MAPPER.createObjectNode().put("type", "seen");
    seen.putArray("ids").add("a").add("b").add("c").add("d").add("e").add("f").add("g");
    reconfigure("s", withRecords(seen, sweep()));

    HarvestRunSummary summary = run("s");

    assertThat(summary.sweepApplied()).isFalse();
    assertThat(summary.sweepBlockedCount()).isEqualTo(3L);
    assertThat(live()).isEqualTo(10);
    assertThat(source().sweepBlockedCount()).isEqualTo(3L);
  }

  @Test
  void smallDeletesAreBelowTheGuardFloor() throws Exception {
    seedItems("a", "b", "c");
    reconfigure("s", withRecords(document("a", "EVENT content of a"), sweep()));

    run("s");

    assertThat(live()).isEqualTo(1);
  }

  @Test
  void aCleanSweepClearsTheFlag() throws Exception {
    seedItems("a");
    store.setSweepBlocked("s", 5L);
    reconfigure("s", withRecords(document("a", "EVENT content of a"), sweep()));

    run("s");

    assertThat(source().sweepBlockedCount()).isNull();
  }

  @Test
  void tombstoneDeletesAnItem() throws Exception {
    seedItems("a", "b");
    reconfigure("s", withRecords(MAPPER.createObjectNode().put("type", "tombstone").put("id", "a")));

    HarvestRunSummary summary = run("s");

    assertThat(summary.itemsDeleted()).isEqualTo(1);
    assertThat(live()).isEqualTo(1);
  }

  @Test
  void authErrorPausesTheSource() throws Exception {
    HarvestRunSummary summary = scripted(withRecords(error("auth", "denied")));

    assertThat(summary.errorClass()).isEqualTo(ErrorClass.AUTH);
    assertThat(source().status()).isEqualTo(SourceStatus.NEEDS_AUTH);
  }

  @Test
  void retryAfterIsReported() throws Exception {
    ObjectNode limited = error("rate_limited", "slow").put("retryAfter", 90);

    assertThat(scripted(withRecords(limited)).retryAfterSeconds()).isEqualTo(90L);
  }

  @Test
  void protocolViolationFailsTheRun() throws Exception {
    HarvestRunSummary summary = scripted(config().put("rawLine", "garbage"));

    assertThat(summary.status()).isEqualTo(RunStatus.FAILED);
    assertThat(summary.errorClass()).isEqualTo(ErrorClass.FATAL);
    assertThat(summary.errorMessage()).contains("protocol");
  }

  @Test
  void timeoutFailsAsTransient() throws Exception {
    createSource("s", config().put("sleepMs", 60_000), SourceMode.FULL);

    HarvestRunSummary summary = run("s", false, Duration.ofSeconds(3));

    assertThat(summary.status()).isEqualTo(RunStatus.FAILED);
    assertThat(summary.errorClass()).isEqualTo(ErrorClass.TRANSIENT);
  }

  @Test
  void changedEntrypointPreventsLaunch() throws Exception {
    Path pidFile = root.resolve("pid");
    Files.writeString(root.resolve("scripted/run.sh"), "#!/bin/sh\nexit 0\n");

    HarvestRunSummary summary = scripted(config().put("pidFile", pidFile.toString()));

    assertThat(summary.status()).isEqualTo(RunStatus.FAILED);
    assertThat(summary.errorClass()).isEqualTo(ErrorClass.CONFIG);
    assertThat(pidFile).doesNotExist();
  }

  @Test
  void unknownHarvesterFailsTheRun() throws Exception {
    store.createSource(new HarvestSource("s", profileId, "absent", "{}", SourceMode.FULL, 3600, null, null,
      SourceStatus.ACTIVE, null, T0, 0, null, T0));

    HarvestRunSummary summary = run("s");

    assertThat(summary.status()).isEqualTo(RunStatus.FAILED);
    assertThat(summary.errorClass()).isEqualTo(ErrorClass.CONFIG);
  }

  @Test
  void unknownSourceIsRejected() {
    assertThatThrownBy(() -> run("absent")).isInstanceOf(ExecutionException.class)
      .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  private static ObjectNode secretUpdate(String name, String value) {
    return MAPPER.createObjectNode().put("type", "secret_update").put("name", name).put("value", value);
  }

  @Test
  void rotatedSecretIsSaved() throws Exception {
    scripted(withRecords(secretUpdate("token", "rotated")));

    assertThat(secrets.secrets("s")).containsEntry("token", "rotated");
  }

  @Test
  void undeclaredSecretUpdateIsIgnored() throws Exception {
    scripted(withRecords(secretUpdate("sneaky", "value")));

    assertThat(secrets.secrets("s")).doesNotContainKey("sneaky");
  }

  @Test
  void secretsAreScrubbedFromTheRunRecord() throws Exception {
    secrets.put("s", "token", TOKEN);
    ObjectNode config = withRecords(error("fatal", "rejected " + TOKEN));
    config.put("stderr", "using " + TOKEN);

    HarvestRunSummary summary = scripted(config);

    assertThat(summary.errorMessage()).doesNotContain(TOKEN).contains("rejected");
    assertThat(lastRun().errorMessage()).doesNotContain(TOKEN);
    assertThat(lastRun().stderrTail()).doesNotContain(TOKEN).contains("using");
  }

  @Test
  void dryRunWritesNothingButCounts() throws Exception {
    seedItems("a");
    int calls = model.extractions.get();
    reconfigure("s", withRecords(document("a", "EVENT changed content"), document("b", "EVENT brand new"), checkpoint(9), sweep()));

    HarvestRunSummary summary = run("s", true, Duration.ofSeconds(60));

    assertThat(summary.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(summary.itemsSeen()).isEqualTo(2);
    assertThat(summary.itemsChanged()).isEqualTo(2);
    assertThat(summary.chars()).isGreaterThan(0);
    assertThat(summary.estimatedChunks()).isGreaterThan(0);
    assertThat(model.extractions.get()).isEqualTo(calls);
    assertThat(live()).isEqualTo(1);
    assertThat(source().cursorJson()).isNull();
    assertThat(search("brand")).isEmpty();
    assertThat(lastRun().dryRun()).isTrue();
  }

  @Test
  void dryRunStillSavesARotatedSecret() throws Exception {
    createSource("s", withRecords(secretUpdate("token", "rotated")), SourceMode.FULL);

    run("s", true, Duration.ofSeconds(60));

    assertThat(secrets.secrets("s")).containsEntry("token", "rotated");
  }

  @Test
  void cancellationEndsTheRunAndTheProcess() throws Exception {
    Path pidFile = root.resolve("pid");
    createSource("s", config().put("pidFile", pidFile.toString()).put("sleepMs", 60_000), SourceMode.FULL);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CompletableFuture<Void> finished = new CompletableFuture<>();
    UUID task = tasks.submit("harvest", "p", progress -> {
      try {
        service(Duration.ofSeconds(60)).run("s", false, progress);
      } catch (RuntimeException e) {
        failure.set(e);
        throw e;
      } finally {
        finished.complete(null);
      }
      return MAPPER.createObjectNode();
    });
    for (int i = 0; i < 300 && !Files.exists(pidFile); i++) {
      Thread.sleep(100);
    }
    String pid = Files.readString(pidFile);

    tasks.cancel(task);
    finished.get(30, TimeUnit.SECONDS);

    assertThat(failure.get()).isInstanceOf(TaskCancelledException.class);
    assertThat(lastRun().status()).isEqualTo(RunStatus.CANCELLED);
    for (int i = 0; i < 50 && ProcessHandle.of(Long.parseLong(pid.strip())).isPresent(); i++) {
      Thread.sleep(100);
    }
    assertThat(ProcessHandle.of(Long.parseLong(pid.strip()))).isEmpty();
  }

  @Test
  void rawModeRunsWithoutTheModel() throws Exception {
    createSource("s", withRecords(document("a", "searchable zanzibar")), SourceMode.RAW);

    HarvestRunSummary summary = run("s");

    assertThat(summary.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(model.extractions.get()).isZero();
    assertThat(live()).isEqualTo(1);
  }
}
