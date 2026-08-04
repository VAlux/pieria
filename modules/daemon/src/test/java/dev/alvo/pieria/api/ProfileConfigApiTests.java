package dev.alvo.pieria.api;

import dev.alvo.pieria.config.VerifyMode;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.api.controller.ProfileConfigController;
import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.ProfileConfigService;
import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.toml.ConfigCodec;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.retrieval.DeterministicQueryAnalyzer;
import dev.alvo.pieria.retrieval.RecallResult;
import dev.alvo.pieria.retrieval.RetrievalService;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
import dev.alvo.pieria.storage.NoOpCodeIndexStore;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end test of the per-profile config flow against a real SQLite store: PUT overrides →
 * the per-profile pipeline observably changes (the GRAPH channel disappears from debug recall
 * for that profile only), GET returns the effective config, DELETE restores the global one, and
 * overrides survive a resolver restart (table-backed durability). The whitelist rejects
 * process-global keys so a stray project file can never reach shared state.
 */
class ProfileConfigApiTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteMemoryStore store;
  private EffectiveConfigResolver resolver;
  private ProfileConfigController controller;
  private RetrievalService retrieval;

  private static PieriaProperties globalProps() {
    return new PieriaProperties(null, null, null, null,
      new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS, 1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70),
      new PieriaProperties.Retrieval(true, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 1.0, 1.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.60),
      null);
  }

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-config-api-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(dataSource).load().migrate();

    store = new SqliteMemoryStore(JdbcClient.create(dataSource));
    resolver = new EffectiveConfigResolver(globalProps(), store);
    controller = new ProfileConfigController(new ProfileConfigService(store, resolver));
    retrieval = new RetrievalService(store, new FakeModelGateway(), new DeterministicQueryAnalyzer(),
      new NoOpCodeIndexStore(), resolver);
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

  private static JsonNode json(String content) {
    return ConfigCodec.parseJson(content);
  }

  @Test
  void putDisablesGraphChannelForThatProfileOnly() {
    store.getOrCreateProfile("tuned");
    store.getOrCreateProfile("untouched");

    controller.put("tuned", json("{\"retrieval\":{\"weight-graph\":0.0,\"weight-code-graph\":0.0}}"));

    RecallResult tuned = retrieval.recall("tuned", "anything", 10, true);
    assertThat(tuned.diagnostics().channels())
      .noneMatch(c -> c.channel() == RetrievalChannelType.GRAPH || c.channel() == RetrievalChannelType.CODE_GRAPH);

    RecallResult untouched = retrieval.recall("untouched", "anything", 10, true);
    assertThat(untouched.diagnostics().channels())
      .anyMatch(c -> c.channel() == RetrievalChannelType.GRAPH);
  }

  @Test
  void putRecallModeSetsTheProfileDefaultTier() {
    store.getOrCreateProfile("evidence-only");
    store.getOrCreateProfile("full");

    // Lowercase on the wire also exercises RecallMode's case-insensitive binding through ConfigCodec.
    controller.put("evidence-only", json("{\"retrieval\":{\"recall-mode\":\"evidence\"}}"));

    // No request-level mode ⇒ the profile's configured default applies: EVIDENCE skips synthesis.
    RecallResult evidenceOnly = retrieval.recall("evidence-only", "anything", 10, false);
    assertThat(evidenceOnly.answer()).isNull();

    // An untouched profile keeps the global default (SYNTHESIZED) and still composes an answer.
    RecallResult full = retrieval.recall("full", "anything", 10, false);
    assertThat(full.answer()).isNotNull();
  }

  @Test
  void putReturnsEffectiveConfigAndGetAgrees() {
    controller.put("p", json("{\"retrieval\":{\"rrf-k\":30}}"));

    JsonNode effective = controller.get("p");
    assertThat(effective.at("/retrieval/rrf-k").asInt()).isEqualTo(30);      // overridden
    assertThat(effective.at("/retrieval/weight-exact-key").asDouble()).isEqualTo(3.0); // inherited
    assertThat(effective.at("/ingestion/chunk-size-chars").asInt()).isEqualTo(10000);  // inherited
  }

  @Test
  void getOnUnknownProfileReturnsGlobalConfigWithoutCreatingIt() {
    JsonNode effective = controller.get("ghost");
    assertThat(effective.at("/retrieval/rrf-k").asInt()).isEqualTo(60);
    assertThat(store.findProfile("ghost")).isEmpty();
  }

  @Test
  void deleteRestoresGlobalConfig() {
    controller.put("p", json("{\"retrieval\":{\"rrf-k\":30}}"));
    controller.delete("p");
    assertThat(controller.get("p").at("/retrieval/rrf-k").asInt()).isEqualTo(60);
  }

  @Test
  void overridesSurviveResolverRestart() {
    controller.put("p", json("{\"ingestion\":{\"chunk-size-chars\":8000}}"));

    // Fresh resolver over the same store simulates a daemon restart.
    EffectiveConfigResolver restarted = new EffectiveConfigResolver(globalProps(), store);
    ProfileConfigController restartedController =
      new ProfileConfigController(new ProfileConfigService(store, restarted));
    assertThat(restartedController.get("p").at("/ingestion/chunk-size-chars").asInt()).isEqualTo(8000);
  }

  @Test
  void emptyBodyClearsOverrides() {
    controller.put("p", json("{\"retrieval\":{\"rrf-k\":30}}"));
    controller.put("p", json("{}"));
    assertThat(controller.get("p").at("/retrieval/rrf-k").asInt()).isEqualTo(60);
    assertThat(store.getProfileConfig(store.findProfile("p").orElseThrow().id())).isEmpty();
  }

  @Test
  void whitelistRejectsProcessGlobalAndUnknownKeys() {
    assertThatThrownBy(() -> controller.put("p", json("{\"model\":{\"embedding-dimension\":2048}}")))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("model");

    assertThatThrownBy(() -> controller.put("p", json("{\"db\":{\"path\":\"/tmp/evil.db\"}}")))
      .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> controller.put("p", json("{\"ingestion\":{\"outbox-batch-size\":1}}")))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("outbox-batch-size");

    assertThatThrownBy(() -> controller.put("p", json("{\"retrieval\":{\"no-such-knob\":1}}")))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("no-such-knob");

    // Nothing was persisted by the rejected calls.
    assertThat(store.findProfile("p")).isEmpty();
  }
}
