package dev.alvo.pieria.config;

import dev.alvo.pieria.config.schema.ConfigSchemaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalConfigServiceTests {

  @TempDir
  Path configDir;

  private MockEnvironment environment;
  private GlobalConfigService service;
  private Path propertiesFile;

  @BeforeEach
  void setUp() {
    propertiesFile = configDir.resolve("pieria.properties");
    environment = new MockEnvironment();
    environment.setProperty("pieria.daemon.port", "8077");
    environment.setProperty("pieria.provider.name", "ollama");
    environment.setProperty("pieria.model.embedding-dimension", "1024");
    environment.setProperty("pieria.reminiscence.parallelism", "8");
    service = new GlobalConfigService(new ConfigSchemaService(), environment, pathResolver(configDir));
  }

  /**
   * A real resolver pointed at the temp directory — the same pattern BootstrapServiceTests uses.
   * AppDataPathResolver null-guards pieria.db(), so an all-null PieriaProperties is safe here.
   */
  static AppDataPathResolver pathResolver(Path dir) {
    return new AppDataPathResolver(
      new AppDataProperties(dir.toString(), dir.toString(), dir.toString(),
        dir.toString(), dir.toString()),
      new PieriaProperties(null, null, null, null, null, null, null));
  }

  @Test
  void reportsTheRunningValueAndMarksUntouchedKeysAsDefault() {
    GlobalConfigEntry port = entry("pieria.daemon.port");

    assertThat(port.value()).isEqualTo("8077");
    assertThat(port.fileValue()).isNull();
    assertThat(port.provenance()).isEqualTo("default");
    assertThat(port.restartPending()).isFalse();
    assertThat(port.tier()).isEqualTo("restart");
  }

  @Test
  void writingAKeyMarksItSet() {
    // No global key is "live" tier (pieria.properties is bound once at startup and never
    // re-read), so a plain write is exercised here for the set/fileValue bookkeeping; restart
    // reporting itself is covered by writingARestartKeyReportsItAndStaysPendingUntilTheDaemonRestarts.
    GlobalConfigService.ApplyResult result =
      service.apply(Map.of("pieria.reminiscence.parallelism", "16"), false);

    assertThat(result.written()).containsExactly("pieria.reminiscence.parallelism");
    assertThat(entry("pieria.reminiscence.parallelism").provenance()).isEqualTo("set");
    assertThat(entry("pieria.reminiscence.parallelism").fileValue()).isEqualTo("16");
  }

  @Test
  void writingARestartKeyReportsItAndStaysPendingUntilTheDaemonRestarts() {
    service.apply(Map.of("pieria.daemon.port", "9090"), false);

    GlobalConfigEntry port = entry("pieria.daemon.port");
    assertThat(port.value()).isEqualTo("8077");        // the running daemon, unchanged
    assertThat(port.fileValue()).isEqualTo("9090");    // what a restart would pick up
    assertThat(port.restartPending()).isTrue();
    assertThat(port.provenance()).isEqualTo("set");
  }

  @Test
  void restartRequiredListsExactlyTheWrittenKeysSinceNoGlobalKeyIsLive() {
    Map<String, String> updates = new HashMap<>();
    updates.put("pieria.reminiscence.parallelism", "16");
    updates.put("pieria.daemon.port", "9090");

    GlobalConfigService.ApplyResult result = service.apply(updates, false);

    assertThat(result.restartRequired()).containsExactlyInAnyOrder(
      "pieria.reminiscence.parallelism", "pieria.daemon.port");
  }

  @Test
  void lockedKeysAreRefusedWithoutAnExplicitAcknowledgement() {
    assertThatThrownBy(() -> service.apply(Map.of("pieria.model.embedding-dimension", "768"), false))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("pieria.model.embedding-dimension")
      .hasMessageContaining("acknowledge");

    assertThat(Files.exists(propertiesFile)).isFalse();
  }

  @Test
  void lockedKeysAreWrittenWhenAcknowledged() {
    GlobalConfigService.ApplyResult result =
      service.apply(Map.of("pieria.model.embedding-dimension", "768"), true);

    assertThat(result.written()).containsExactly("pieria.model.embedding-dimension");
    assertThat(result.restartRequired()).containsExactly("pieria.model.embedding-dimension");
  }

  @Test
  void unknownKeysAreRejectedSoAStrayPayloadCannotReachProcessState() {
    assertThatThrownBy(() -> service.apply(Map.of("pieria.secret.backdoor", "1"), true))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("pieria.secret.backdoor");
  }

  @Test
  void profileScopedKeysAreRejectedOnTheGlobalSurface() {
    assertThatThrownBy(() -> service.apply(Map.of("retrieval.rrf-k", "12"), true))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("retrieval.rrf-k");
  }

  @Test
  void valuesAreValidatedAgainstTheDeclaredKind() {
    assertThatThrownBy(() -> service.apply(Map.of("pieria.daemon.port", "eight-thousand"), false))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("pieria.daemon.port");

    assertThatThrownBy(() -> service.apply(Map.of("pieria.provider.type", "bedrock"), false))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("bedrock");
  }

  @Test
  void aNullValueClearsTheKeyBackToTheShippedDefault() throws IOException {
    service.apply(Map.of("pieria.reminiscence.parallelism", "16"), false);
    assertThat(entry("pieria.reminiscence.parallelism").provenance()).isEqualTo("set");

    Map<String, String> clearing = new HashMap<>();
    clearing.put("pieria.reminiscence.parallelism", null);
    GlobalConfigService.ApplyResult result = service.apply(clearing, false);

    assertThat(result.cleared()).containsExactly("pieria.reminiscence.parallelism");
    assertThat(entry("pieria.reminiscence.parallelism").provenance()).isEqualTo("default");
    assertThat(Files.readAllLines(propertiesFile))
      .noneMatch(line -> line.startsWith("pieria.reminiscence.parallelism="));
  }

  @Test
  void nothingIsWrittenWhenAnyKeyInTheBatchIsInvalid() {
    Map<String, String> updates = new HashMap<>();
    updates.put("pieria.reminiscence.parallelism", "16");
    updates.put("pieria.daemon.port", "not-a-port");

    assertThatThrownBy(() -> service.apply(updates, false))
      .isInstanceOf(IllegalArgumentException.class);

    assertThat(Files.exists(propertiesFile)).isFalse();
  }

  @Test
  void everyGlobalSchemaFieldIsReported() {
    List<GlobalConfigEntry> entries = service.effective();

    assertThat(entries).hasSize(new ConfigSchemaService().forScope("global").size());
    assertThat(entries).extracting(GlobalConfigEntry::label).doesNotContainNull();
  }

  private GlobalConfigEntry entry(String key) {
    return service.effective().stream()
      .filter(candidate -> candidate.key().equals(key))
      .findFirst()
      .orElseThrow(() -> new AssertionError("no entry for " + key));
  }
}
