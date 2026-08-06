package dev.alvo.pieria.config.toml;

import dev.alvo.pieria.config.model.DaemonOverrides;
import dev.alvo.pieria.config.model.DiscoveryConfig;
import dev.alvo.pieria.config.model.PieriaConfigFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end shape tests: TOML file → tree → merge → bound records, and the persisted-JSON
 * round-trip for {@link DaemonOverrides}.
 */
class PieriaConfigBindingTests {

  @TempDir
  Path dir;

  private final PieriaTomlLoader loader = new PieriaTomlLoader();

  private ObjectNode loadToml(String name, String content) throws IOException {
    Path file = dir.resolve(name);
    Files.writeString(file, content);
    return loader.load(file);
  }

  @Test
  void missingFileLoadsAsEmptyObject() throws IOException {
    assertThat(loader.load(dir.resolve("absent.toml")).isEmpty()).isTrue();
  }

  @Test
  void kebabCaseTomlBindsToRecords() throws IOException {
    ObjectNode root = loadToml("config.toml", """
      [discovery]
      source-extensions = ["java", "sql"]
      max-file-bytes = 2048

      [pieria.retrieval]
      weight-graph = 0.0
      rrf-k = 30
      channel-timeout-ms = 1500

      [pieria.ingestion]
      chunk-size-chars = 8000
      """);

    PieriaConfigFile config = ConfigCodec.bind(root, PieriaConfigFile.class);

    assertThat(config.discovery().sourceExtensions()).containsExactlyInAnyOrder("java", "sql");
    assertThat(config.discovery().maxFileBytes()).isEqualTo(2048);
    // Absent keys inherit code defaults.
    assertThat(config.discovery().skipDirs()).isEqualTo(DiscoveryConfig.DEFAULT_SKIP_DIRS);
    assertThat(config.discovery().buildMarkers()).isEqualTo(DiscoveryConfig.DEFAULT_BUILD_MARKERS);

    assertThat(config.pieria().retrieval().weightGraph()).isZero();
    assertThat(config.pieria().retrieval().rrfK()).isEqualTo(30);
    assertThat(config.pieria().retrieval().channelTimeoutMs()).isEqualTo(1500);
    assertThat(config.pieria().retrieval().weightExactKey()).isNull(); // inherit
    assertThat(config.pieria().ingestion().chunkSizeChars()).isEqualTo(8000);
  }

  @Test
  void emptyTreeBindsToAllDefaults() {
    PieriaConfigFile config = ConfigCodec.bind(null, PieriaConfigFile.class);
    assertThat(config.discovery()).isEqualTo(DiscoveryConfig.defaults());
    assertThat(config.pieria().isEmpty()).isTrue();
  }

  @Test
  void projectLayerOverridesGlobalLayer() throws IOException {
    ObjectNode global = loadToml("global.toml", """
      [discovery]
      max-file-bytes = 4096

      [pieria.retrieval]
      rrf-k = 30
      weight-graph = 2.0
      """);
    ObjectNode project = loadToml("project.toml", """
      [pieria.retrieval]
      weight-graph = 0.0
      """);

    PieriaConfigFile config =
      ConfigCodec.bind(ConfigMerge.mergeAll(global, project), PieriaConfigFile.class);

    assertThat(config.discovery().maxFileBytes()).isEqualTo(4096); // from global
    assertThat(config.pieria().retrieval().rrfK()).isEqualTo(30); // from global
    assertThat(config.pieria().retrieval().weightGraph()).isZero(); // project wins
  }

  @Test
  void daemonOverridesJsonRoundTripOmitsNulls() {
    DaemonOverrides overrides = new DaemonOverrides(
      new DaemonOverrides.Ingestion(8000, null, null, null, null, null),
      new DaemonOverrides.Retrieval(null, null, null, null, null, null, null, 0.0,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null));

    String json = ConfigCodec.toJson(overrides);
    assertThat(json).contains("chunk-size-chars").contains("weight-graph");
    assertThat(json).doesNotContain("rrf-k").doesNotContain("null");

    DaemonOverrides back = ConfigCodec.bind(ConfigCodec.parseJson(json), DaemonOverrides.class);
    assertThat(back).isEqualTo(overrides);
  }

  @Test
  void explicitlyEmptyListIsHonoredNotDefaulted() throws IOException {
    ObjectNode root = loadToml("config.toml", """
      [discovery]
      build-markers = []
      """);
    PieriaConfigFile config = ConfigCodec.bind(root, PieriaConfigFile.class);
    assertThat(config.discovery().buildMarkers()).isEmpty();
  }

  @Test
  void unknownKeysAreIgnoredOnBind() throws IOException {
    ObjectNode root = loadToml("config.toml", """
      [discovery]
      max-file-bytes = 512
      some-future-key = true
      """);
    PieriaConfigFile config = ConfigCodec.bind(root, PieriaConfigFile.class);
    assertThat(config.discovery().maxFileBytes()).isEqualTo(512);
  }
}
