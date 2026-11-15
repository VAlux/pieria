package dev.alvo.pieria.harvest;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.storage.SqliteMemoryStore;
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
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisabledOnOs(OS.WINDOWS)
class HarvestAuthorizationServiceTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

  @TempDir
  Path root;

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteHarvestStore store;
  private HarvestSecretStore secrets;
  private HarvestAuthorizationService service;
  private String profileId;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-harvest-auth-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(dataSource).load().migrate();
    JdbcClient jdbc = JdbcClient.create(dataSource);
    SqliteMemoryStore memories = new SqliteMemoryStore(jdbc);
    store = new SqliteHarvestStore(jdbc);
    HarvestPaths paths = new HarvestPaths(root, root.resolve("no-bin"));
    secrets = new HarvestSecretStore(paths);
    profileId = memories.getOrCreateProfile("p").id();
    install(paths);
    service = new HarvestAuthorizationService(store, new HarvesterRegistry(paths), secrets);
  }

  @AfterEach
  void tearDown() throws Exception {
    service.shutdown();
    dataSource.close();
    Files.deleteIfExists(dbFile);
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-wal"));
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-shm"));
  }

  /** Two harvesters on the same script: one that signs in, and one that does not declare it. */
  private void install(HarvestPaths paths) throws Exception {
    StringBuilder pins = new StringBuilder("{");
    for (String id : new String[] {"signin", "scripted"}) {
      Path dir = Files.createDirectories(root.resolve(id));
      Path script = dir.resolve("run.sh");
      Files.writeString(script, "#!/bin/sh\nexec '" + ProcessHandle.current().info().command().orElseThrow() + "' -cp '"
        + System.getProperty("java.class.path") + "' " + ScriptedHarvester.class.getName() + " \"$@\"\n");
      assertThat(script.toFile().setExecutable(true)).isTrue();
      String capabilities = id.equals("signin") ? "\"incremental\", \"oauth-device-code\"" : "\"incremental\"";
      Files.writeString(dir.resolve("harvester.toml"), """
        id = "%s"
        name = "Scripted"
        version = "1"
        protocol = 1
        command = ["./run.sh"]
        capabilities = [%s]

        [[config]]
        key = "refreshToken"
        kind = "secret"
        label = "Refresh token"
        """.formatted(id, capabilities));
      if (pins.length() > 1) {
        pins.append(',');
      }
      pins.append('"').append(id).append("\":{\"entrypoint\":\"").append(Hash.sha256Hex(script))
        .append("\",\"manifest\":\"").append(Hash.sha256Hex(dir.resolve("harvester.toml"))).append("\"}");
    }
    Files.writeString(paths.pinsFile(), pins.append('}').toString());
  }

  private void createSource(String harvester, ObjectNode config, SourceStatus status) {
    store.createSource(new HarvestSource("s", profileId, harvester, config.toString(), SourceMode.FULL, 3600, null, null,
      status, null, T0, 0, null, T0));
  }

  private void createSource(ObjectNode config) {
    createSource("signin", config, SourceStatus.ACTIVE);
  }

  private static ObjectNode code(int expiresIn) {
    return MAPPER.createObjectNode().put("type", "device_code").put("verificationUri", "https://microsoft.com/devicelogin")
      .put("userCode", "ABCD").put("expiresIn", expiresIn);
  }

  private static ObjectNode secret(String value) {
    return MAPPER.createObjectNode().put("type", "secret_update").put("name", "refreshToken").put("value", value);
  }

  private static ObjectNode fail(String errorClass, String message) {
    return MAPPER.createObjectNode().put("class", errorClass).put("message", message);
  }

  private static ObjectNode script(ObjectNode[] records, Long sleepMs, ObjectNode... after) {
    ObjectNode config = MAPPER.createObjectNode();
    ArrayNode before = config.putArray("authorizeRecords");
    for (ObjectNode record : records) {
      before.add(record);
    }
    if (sleepMs != null) {
      config.put("authorizeSleepMs", sleepMs);
    }
    ArrayNode then = config.putArray("authorizeAfterRecords");
    for (ObjectNode record : after) {
      then.add(record);
    }
    return config;
  }

  private AuthorizationState awaitStatus(Predicate<AuthorizationState> done, Duration within) throws InterruptedException {
    Instant deadline = Instant.now().plus(within);
    while (Instant.now().isBefore(deadline)) {
      Optional<AuthorizationState> state = service.status("s");
      if (state.isPresent() && done.test(state.get())) {
        return state.get();
      }
      Thread.sleep(50);
    }
    throw new AssertionError("status never settled: " + service.status("s"));
  }

  private AuthorizationState awaitFinished(Duration within) throws InterruptedException {
    return awaitStatus(state -> state.status() != AuthorizationState.Status.PENDING, within);
  }

  private static boolean alive(Path pidFile) throws Exception {
    long pid = Long.parseLong(Files.readString(pidFile).trim());
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  private static void awaitDead(Path pidFile) throws Exception {
    Instant deadline = Instant.now().plusSeconds(10);
    while (alive(pidFile) && Instant.now().isBefore(deadline)) {
      Thread.sleep(50);
    }
    assertThat(alive(pidFile)).isFalse();
  }

  private static void awaitFile(Path file) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(20);
    while (!Files.exists(file) && Instant.now().isBefore(deadline)) {
      Thread.sleep(50);
    }
    assertThat(file).exists();
  }

  @Test
  void startReturnsThePendingCode() throws Exception {
    createSource(script(new ObjectNode[] {code(900)}, 500L, secret("r1")));

    AuthorizationState started = service.start("s");

    assertThat(started.status()).isEqualTo(AuthorizationState.Status.PENDING);
    assertThat(started.status().wire()).isEqualTo("pending");
    assertThat(started.userCode()).isEqualTo("ABCD");
    assertThat(started.verificationUri()).isEqualTo("https://microsoft.com/devicelogin");
    assertThat(started.expiresAt()).isBetween(Instant.now().plusSeconds(895), Instant.now().plusSeconds(905));
    AuthorizationState finished = awaitFinished(Duration.ofSeconds(10));
    assertThat(finished.status()).isEqualTo(AuthorizationState.Status.AUTHORIZED);
    assertThat(finished.userCode()).isEqualTo("ABCD");
    assertThat(secrets.secrets("s")).containsEntry("refreshToken", "r1");
  }

  @Test
  void authorizedSourceLeavesNeedsAuth() throws Exception {
    createSource("signin", script(new ObjectNode[] {code(900), secret("r1")}, null), SourceStatus.NEEDS_AUTH);

    service.start("s");
    awaitFinished(Duration.ofSeconds(10));

    assertThat(store.findSource("s").orElseThrow().status()).isEqualTo(SourceStatus.ACTIVE);
  }

  @Test
  void authorizedPausedSourceStaysPaused() throws Exception {
    createSource("signin", script(new ObjectNode[] {code(900), secret("r1")}, null), SourceStatus.PAUSED);

    service.start("s");
    assertThat(awaitFinished(Duration.ofSeconds(10)).status()).isEqualTo(AuthorizationState.Status.AUTHORIZED);

    assertThat(store.findSource("s").orElseThrow().status()).isEqualTo(SourceStatus.PAUSED);
  }

  @Test
  void secretWithoutACodeIsAuthorized() throws Exception {
    createSource("signin", script(new ObjectNode[] {secret("r1")}, null), SourceStatus.NEEDS_AUTH);

    AuthorizationState started = service.start("s");
    AuthorizationState finished = started.status() == AuthorizationState.Status.PENDING ? awaitFinished(Duration.ofSeconds(10)) : started;

    assertThat(finished.status()).isEqualTo(AuthorizationState.Status.AUTHORIZED);
    assertThat(finished.userCode()).isNull();
    assertThat(secrets.secrets("s")).containsEntry("refreshToken", "r1");
    assertThat(store.findSource("s").orElseThrow().status()).isEqualTo(SourceStatus.ACTIVE);
  }

  @Test
  void errorBeforeTheCodeFailsAtOnce() {
    createSource(script(new ObjectNode[0], null).set("authorizeFail", fail("config", "clientId is wrong")));

    AuthorizationState started = service.start("s");

    assertThat(started.status()).isEqualTo(AuthorizationState.Status.FAILED);
    assertThat(started.errorClass()).isEqualTo(ErrorClass.CONFIG);
    assertThat(started.error()).isEqualTo("clientId is wrong");
    assertThat(started.userCode()).isNull();
  }

  @Test
  void errorAfterTheCodeFails() throws Exception {
    createSource(script(new ObjectNode[] {code(900)}, 200L).set("authorizeFail", fail("auth", "declined")));

    assertThat(service.start("s").status()).isEqualTo(AuthorizationState.Status.PENDING);
    AuthorizationState finished = awaitFinished(Duration.ofSeconds(10));

    assertThat(finished.status()).isEqualTo(AuthorizationState.Status.FAILED);
    assertThat(finished.errorClass()).isEqualTo(ErrorClass.AUTH);
    assertThat(finished.error()).isEqualTo("declined");
  }

  @Test
  void exitWithoutASecretFails() throws Exception {
    createSource(script(new ObjectNode[] {code(900)}, null));

    service.start("s");
    AuthorizationState finished = awaitFinished(Duration.ofSeconds(10));

    assertThat(finished.status()).isEqualTo(AuthorizationState.Status.FAILED);
    assertThat(finished.errorClass()).isEqualTo(ErrorClass.FATAL);
    assertThat(finished.error()).isEqualTo("the harvester finished without saving a sign-in");
  }

  @Test
  void expiredSessionIsKilled() throws Exception {
    Path pidFile = root.resolve("pid");
    createSource(script(new ObjectNode[] {code(1)}, 120_000L).put("pidFile", pidFile.toString()));

    service.start("s");
    AuthorizationState finished = awaitFinished(Duration.ofSeconds(40));

    assertThat(finished.status()).isEqualTo(AuthorizationState.Status.FAILED);
    assertThat(finished.errorClass()).isEqualTo(ErrorClass.TRANSIENT);
    assertThat(finished.error()).isEqualTo("the sign-in timed out");
    awaitDead(pidFile);
  }

  @Test
  void startAgainReplacesTheSession() throws Exception {
    Path firstPid = root.resolve("pid1");
    createSource(script(new ObjectNode[] {code(900)}, 120_000L).put("pidFile", firstPid.toString()));
    service.start("s");
    awaitFile(firstPid);
    Path secondPid = root.resolve("pid2");
    JdbcClient.create(dataSource).sql("UPDATE harvest_sources SET config_json = ? WHERE id = ?")
      .params(script(new ObjectNode[] {code(600)}, 120_000L).put("pidFile", secondPid.toString()).toString(), "s").update();

    AuthorizationState second = service.start("s");

    awaitDead(firstPid);
    assertThat(second.status()).isEqualTo(AuthorizationState.Status.PENDING);
    assertThat(service.status("s")).get().satisfies(state -> {
      assertThat(state.status()).isEqualTo(AuthorizationState.Status.PENDING);
      assertThat(state.expiresAt()).isBefore(Instant.now().plusSeconds(700));
    });
  }

  @Test
  void cancelKillsAndForgets() throws Exception {
    Path pidFile = root.resolve("pid");
    createSource(script(new ObjectNode[] {code(900)}, 120_000L).put("pidFile", pidFile.toString()));
    service.start("s");
    awaitFile(pidFile);

    service.cancel("s");

    awaitDead(pidFile);
    assertThat(service.status("s")).isEmpty();
  }

  @Test
  void harvesterWithoutTheCapabilityIsRefused() {
    createSource("scripted", MAPPER.createObjectNode(), SourceStatus.ACTIVE);

    assertThatThrownBy(() -> service.start("s")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has no sign-in");
  }

  @Test
  void unknownSourceIsRefused() {
    assertThatThrownBy(() -> service.start("absent")).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("unknown harvest source: absent");
  }

  @Test
  void secondDeviceCodeIsAViolation() throws Exception {
    // Raw lines, so the harvester SDK's own guard is not what refuses the second code.
    ObjectNode config = script(new ObjectNode[0], null);
    config.putArray("authorizeRawLines").add(code(900)).add(code(900));
    createSource(config);

    AuthorizationState started = service.start("s");
    AuthorizationState finished = started.status() == AuthorizationState.Status.PENDING
      ? awaitFinished(Duration.ofSeconds(10)) : started;

    assertThat(started.userCode()).isEqualTo("ABCD");
    assertThat(finished.status()).isEqualTo(AuthorizationState.Status.FAILED);
    assertThat(finished.errorClass()).isEqualTo(ErrorClass.FATAL);
    assertThat(finished.error()).startsWith("protocol violation");
  }

  @Test
  void storedSecretsAreScrubbedFromErrors() throws Exception {
    secrets.put("s", "refreshToken", "old-refresh-value");
    createSource(script(new ObjectNode[] {code(900)}, null).set("authorizeFail", fail("auth", "token old-refresh-value was refused")));

    service.start("s");
    AuthorizationState finished = awaitFinished(Duration.ofSeconds(10));

    assertThat(finished.error()).isEqualTo("token *** was refused");
  }
}
