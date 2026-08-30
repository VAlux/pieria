package dev.alvo.pieria.api;

import dev.alvo.pieria.api.controller.GlobalConfigController;
import dev.alvo.pieria.config.AppDataPathResolver;
import dev.alvo.pieria.config.AppDataProperties;
import dev.alvo.pieria.config.GlobalConfigService;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.schema.ConfigSchemaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalConfigApiTests {

  @TempDir
  Path configDir;

  private GlobalConfigController controller;

  @BeforeEach
  void setUp() {
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("pieria.daemon.port", "8077");
    environment.setProperty("pieria.reminiscence.parallelism", "8");
    environment.setProperty("pieria.model.embedding-dimension", "1024");

    ConfigSchemaService schema = new ConfigSchemaService();
    AppDataPathResolver paths = new AppDataPathResolver(
      new AppDataProperties(configDir.toString(), configDir.toString(), configDir.toString(),
        configDir.toString(), configDir.toString()),
      new PieriaProperties(null, null, null, null, null, null, null));
    GlobalConfigService service = new GlobalConfigService(schema, environment, paths);
    controller = new GlobalConfigController(service, schema, paths);
  }

  @Test
  void schemaCoversBothScopesSoOneFetchDrivesBothPages() {
    JsonNode schema = controller.schema();

    assertThat(schema.isArray()).isTrue();
    boolean hasProfile = false;
    boolean hasGlobal = false;
    for (JsonNode field : schema) {
      if ("profile".equals(field.get("scope").asString())) hasProfile = true;
      if ("global".equals(field.get("scope").asString())) hasGlobal = true;
    }
    assertThat(hasProfile).isTrue();
    assertThat(hasGlobal).isTrue();
  }

  @Test
  void getReportsEntriesTheConfigFileAndTheRestartCommand() {
    JsonNode body = controller.get();

    assertThat(body.get("entries").isArray()).isTrue();
    assertThat(body.get("entries").size()).isGreaterThan(0);
    assertThat(body.get("configFile").asString()).endsWith("pieria.properties");
    assertThat(body.get("restartCommand").asString()).isEqualTo("pieria daemon restart");
  }

  @Test
  void putWritesAndReportsWhatNeedsARestart() {
    // pieria.reminiscence.parallelism and pieria.daemon.port are both restart-tier: since the
    // daemon binds pieria.properties once at startup, no global key can take effect live, so both
    // written keys are expected to come back as restart-required (see Step 3b / ConfigSchemaTests
    // .noGlobalFieldClaimsToApplyWithoutARestart).
    Map<String, String> values = new HashMap<>();
    values.put("pieria.reminiscence.parallelism", "16");
    values.put("pieria.daemon.port", "9090");

    JsonNode result = controller.put(new GlobalConfigController.GlobalConfigUpdate(values, false));

    assertThat(result.get("written").size()).isEqualTo(2);
    List<String> restartRequired = new ArrayList<>();
    result.get("restart-required").forEach(key -> restartRequired.add(key.asString()));
    assertThat(restartRequired).containsExactlyInAnyOrder(
      "pieria.reminiscence.parallelism", "pieria.daemon.port");
  }

  // The execution-trace properties are the console's only handle on that feature: TraceProperties
  // sits outside PieriaProperties, so EffectiveConfigResolver's per-profile overlay never sees it
  // and the global page is where it is tuned or turned off.
  @Test
  void putAcceptsExecutionTraceKeys() {
    Map<String, String> values = new HashMap<>();
    values.put("pieria.ingestion.trace.enabled", "false");
    values.put("pieria.ingestion.trace.recall-boost", "2.5");
    values.put("pieria.ingestion.trace.tool-denylist", "Read,Grep,Glob");

    JsonNode result = controller.put(new GlobalConfigController.GlobalConfigUpdate(values, false));

    assertThat(result.get("written").size()).isEqualTo(3);
    List<String> restartRequired = new ArrayList<>();
    result.get("restart-required").forEach(key -> restartRequired.add(key.asString()));
    assertThat(restartRequired).contains("pieria.ingestion.trace.enabled");
  }

  // The declared kind is what stops a bad value reaching the file the daemon boots from.
  @Test
  void putRejectsANonNumericTraceBudget() {
    Map<String, String> values = new HashMap<>();
    values.put("pieria.ingestion.trace.max-output-chars", "banana");

    assertThatThrownBy(() ->
      controller.put(new GlobalConfigController.GlobalConfigUpdate(values, false)))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("must be a number");
  }

  @Test
  void putRefusesALockedKeyUntilItIsAcknowledged() {
    Map<String, String> values = new HashMap<>();
    values.put("pieria.model.embedding-dimension", "768");

    assertThatThrownBy(() ->
      controller.put(new GlobalConfigController.GlobalConfigUpdate(values, false)))
      .isInstanceOf(IllegalArgumentException.class);

    JsonNode ok = controller.put(new GlobalConfigController.GlobalConfigUpdate(values, true));
    assertThat(ok.get("written").get(0).asString()).isEqualTo("pieria.model.embedding-dimension");
  }

  @Test
  void putRefusesAProfileScopedKey() {
    Map<String, String> values = new HashMap<>();
    values.put("retrieval.rrf-k", "12");

    assertThatThrownBy(() ->
      controller.put(new GlobalConfigController.GlobalConfigUpdate(values, true)))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("retrieval.rrf-k");
  }

  @Test
  void aRestartTierWriteShowsAsPendingOnTheNextRead() {
    Map<String, String> values = new HashMap<>();
    values.put("pieria.daemon.port", "9090");
    controller.put(new GlobalConfigController.GlobalConfigUpdate(values, false));

    JsonNode body = controller.get();
    JsonNode port = null;
    for (JsonNode entry : body.get("entries")) {
      if ("pieria.daemon.port".equals(entry.get("key").asString())) port = entry;
    }

    assertThat(port).isNotNull();
    assertThat(port.get("value").asString()).isEqualTo("8077");
    assertThat(port.get("file-value").asString()).isEqualTo("9090");
    assertThat(port.get("restart-pending").asBoolean()).isTrue();
  }
}
