package dev.alvo.pieria.api;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.api.controller.ProfileConfigController;
import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.ProfileConfigService;
import dev.alvo.pieria.config.VerifyMode;
import dev.alvo.pieria.config.toml.ConfigCodec;
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

/**
 * The detail view is what lets the console distinguish "overridden to 1.0" from "inherits 1.0".
 * Diffing effective against global cannot: both look identical.
 */
class ProfileConfigDetailTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private ProfileConfigController controller;

  private static PieriaProperties globalProps() {
    return new PieriaProperties(null, null, null, null,
      new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS, 1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70),
      new PieriaProperties.Retrieval(true, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000,
        1.0, 1.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.60, 0.78),
      null);
  }

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-detail", ".db");
    Files.deleteIfExists(dbFile);
    String url = "jdbc:sqlite:" + dbFile;
    dataSource = DataSourceBuilder.create().type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC").url(url).build();
    Flyway.configure().dataSource(dataSource).load().migrate();

    SqliteMemoryStore store = new SqliteMemoryStore(JdbcClient.create(dataSource));
    PieriaProperties props = globalProps();
    EffectiveConfigResolver resolver = new EffectiveConfigResolver(props, store);
    controller = new ProfileConfigController(new ProfileConfigService(store, resolver));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (dataSource != null) dataSource.close();
    Files.deleteIfExists(dbFile);
  }

  @Test
  void detailSeparatesOverriddenFromInheritedEvenWhenValuesAgree() {
    // weight-graph is overridden to exactly the global value; rrf-k is left alone.
    controller.put("alice", ConfigCodec.parseJson(
      "{\"retrieval\":{\"weight-graph\":1.0}}"));

    JsonNode detail = controller.detail("alice");

    assertThat(detail.get("overrides").get("retrieval").has("weight-graph")).isTrue();
    assertThat(detail.get("overrides").get("retrieval").has("rrf-k")).isFalse();
    assertThat(detail.get("effective").get("retrieval").get("weight-graph").asDouble()).isEqualTo(1.0);
    assertThat(detail.get("global").get("retrieval").get("weight-graph").asDouble()).isEqualTo(1.0);
    assertThat(detail.get("global").get("retrieval").get("rrf-k").asInt()).isEqualTo(60);
  }

  @Test
  void detailOfAnUnknownProfileIsAllGlobalAndCreatesNoProfileRow() {
    JsonNode detail = controller.detail("nobody");

    assertThat(detail.get("overrides").isEmpty()).isTrue();
    assertThat(detail.get("effective").get("retrieval").get("rrf-k").asInt()).isEqualTo(60);
    assertThat(detail.get("global").get("retrieval").get("rrf-k").asInt()).isEqualTo(60);
  }

  @Test
  void overriddenValuesShowThroughToEffectiveButNotToGlobal() {
    controller.put("alice", ConfigCodec.parseJson(
      "{\"retrieval\":{\"rrf-k\":12},\"ingestion\":{\"chunk-size-chars\":14000}}"));

    JsonNode detail = controller.detail("alice");

    assertThat(detail.get("effective").get("retrieval").get("rrf-k").asInt()).isEqualTo(12);
    assertThat(detail.get("global").get("retrieval").get("rrf-k").asInt()).isEqualTo(60);
    assertThat(detail.get("overrides").get("ingestion").get("chunk-size-chars").asInt()).isEqualTo(14000);
  }
}
