package dev.alvo.pieria.harvest;

import dev.alvo.pieria.harvester.manifest.HarvesterManifest;
import dev.alvo.pieria.harvester.manifest.ManifestParser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceConfigValidatorTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HarvesterManifest MANIFEST = ManifestParser.parse("""
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

    [[config]]
    key = "count"
    kind = "int"
    label = "Count"

    [[config]]
    key = "on"
    kind = "bool"
    label = "On"

    [[config]]
    key = "mode"
    kind = "enum"
    label = "Mode"
    options = ["a", "b"]

    [[config]]
    key = "tags"
    kind = "list"
    label = "Tags"
    """);

  private static ObjectNode valid() {
    return MAPPER.createObjectNode().put("baseUrl", "https://x");
  }

  private static void rejected(ObjectNode config, Set<String> secrets) {
    assertThatThrownBy(() -> SourceConfigValidator.validate(MANIFEST, config, secrets, true))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsAValidConfig() {
    ObjectNode config = valid().put("count", 3).put("on", true).put("mode", "a");
    config.putArray("tags").add("x");

    assertThatCode(() -> SourceConfigValidator.validate(MANIFEST, config, Set.of("token"), true)).doesNotThrowAnyException();
  }

  @Test
  void rejectsAnUnknownKey() {
    rejected(valid().put("nope", 1), Set.of("token"));
  }

  @Test
  void rejectsASecretInConfig() {
    rejected(valid().put("token", "value"), Set.of("token"));
  }

  @Test
  void rejectsWrongTypes() {
    rejected(MAPPER.createObjectNode().put("baseUrl", 7), Set.of("token"));
    rejected(valid().put("count", "three"), Set.of("token"));
    rejected(valid().put("count", 1.5), Set.of("token"));
    rejected(valid().put("on", "yes"), Set.of("token"));
    rejected(valid().put("tags", "x"), Set.of("token"));
    ObjectNode badList = valid();
    badList.putArray("tags").add(1);
    rejected(badList, Set.of("token"));
  }

  @Test
  void rejectsAValueOutsideEnumOptions() {
    rejected(valid().put("mode", "c"), Set.of("token"));
  }

  @Test
  void rejectsAMissingRequiredField() {
    rejected(MAPPER.createObjectNode(), Set.of("token"));
  }

  @Test
  void rejectsAMissingRequiredSecret() {
    rejected(valid(), Set.of());
  }

  @Test
  void rejectsAnUndeclaredSecret() {
    rejected(valid(), Set.of("token", "other"));
  }

  @Test
  void partialValidationSkipsRequiredChecks() {
    assertThatCode(() -> SourceConfigValidator.validate(MANIFEST, MAPPER.createObjectNode(), Set.of(), false)).doesNotThrowAnyException();
  }
}
