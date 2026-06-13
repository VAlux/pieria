package dev.alvo.pieria.config.toml;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigMergeTests {

  private final JsonMapper mapper = JsonMapper.builder().build();

  private JsonNode json(String content) {
    return mapper.readTree(content);
  }

  @Test
  void overlayScalarReplacesBaseScalar() {
    JsonNode merged = ConfigMerge.deepMerge(json("{\"a\":1,\"b\":2}"), json("{\"b\":3}"));
    assertThat(merged.get("a").asInt()).isEqualTo(1);
    assertThat(merged.get("b").asInt()).isEqualTo(3);
  }

  @Test
  void nestedObjectsMergeRecursively() {
    JsonNode base = json("{\"pieria\":{\"retrieval\":{\"rrf-k\":60,\"weight-graph\":1.0}}}");
    JsonNode overlay = json("{\"pieria\":{\"retrieval\":{\"weight-graph\":0.0}}}");
    JsonNode merged = ConfigMerge.deepMerge(base, overlay);
    assertThat(merged.at("/pieria/retrieval/rrf-k").asInt()).isEqualTo(60);
    assertThat(merged.at("/pieria/retrieval/weight-graph").asDouble()).isZero();
  }

  @Test
  void arraysReplaceWholesaleInsteadOfAppending() {
    JsonNode base = json("{\"discovery\":{\"skip-dirs\":[\".git\",\"build\"]}}");
    JsonNode overlay = json("{\"discovery\":{\"skip-dirs\":[\"vendor\"]}}");
    JsonNode merged = ConfigMerge.deepMerge(base, overlay);
    assertThat(merged.at("/discovery/skip-dirs")).hasSize(1);
    assertThat(merged.at("/discovery/skip-dirs/0").asString()).isEqualTo("vendor");
  }

  @Test
  void absentOverlayKeysInheritBase() {
    JsonNode merged = ConfigMerge.deepMerge(json("{\"a\":{\"x\":1}}"), json("{}"));
    assertThat(merged.at("/a/x").asInt()).isEqualTo(1);
  }

  @Test
  void nullLayersAreSkipped() {
    JsonNode base = json("{\"a\":1}");
    assertThat(ConfigMerge.deepMerge(base, null)).isEqualTo(base);
    assertThat(ConfigMerge.deepMerge(null, base)).isEqualTo(base);
    assertThat(ConfigMerge.mergeAll(null, base, null)).isEqualTo(base);
  }

  @Test
  void inputsAreNotMutated() {
    JsonNode base = json("{\"a\":{\"x\":1}}");
    JsonNode overlay = json("{\"a\":{\"x\":2}}");
    ConfigMerge.deepMerge(base, overlay);
    assertThat(base.at("/a/x").asInt()).isEqualTo(1);
  }

  @Test
  void mergeAllAppliesProjectOverGlobalOverDefaults() {
    JsonNode defaults = json("{\"v\":\"defaults\",\"d\":1,\"g\":1}");
    JsonNode global = json("{\"v\":\"global\",\"g\":2,\"p\":1}");
    JsonNode project = json("{\"v\":\"project\"}");
    JsonNode merged = ConfigMerge.mergeAll(defaults, global, project);
    assertThat(merged.get("v").asString()).isEqualTo("project");
    assertThat(merged.get("d").asInt()).isEqualTo(1);
    assertThat(merged.get("g").asInt()).isEqualTo(2);
    assertThat(merged.get("p").asInt()).isEqualTo(1);
  }
}
