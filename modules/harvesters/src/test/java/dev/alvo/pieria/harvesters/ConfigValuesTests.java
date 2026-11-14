package dev.alvo.pieria.harvesters;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigValuesTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ObjectNode config() {
    return MAPPER.createObjectNode();
  }

  private static void assertConfigError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
    assertThatThrownBy(call).isInstanceOfSatisfying(HarvesterException.class, e -> {
      assertThat(e.errorClass()).isEqualTo(ErrorClass.CONFIG);
      assertThat(e.getMessage()).contains(message);
    });
  }

  @Test
  void stringsRejectsANonList() {
    assertConfigError(() -> ConfigValues.strings(config().put("seeds", "x"), "seeds"), "seeds must be a list");
  }

  @Test
  void stringsReadsAList() throws HarvesterException {
    ObjectNode config = config();
    config.putArray("spaces").add("A").add("B");
    assertThat(ConfigValues.strings(config, "spaces")).containsExactly("A", "B");
    assertThat(ConfigValues.strings(config(), "spaces")).isEmpty();
  }

  @Test
  void stringRejectsANumber() {
    assertConfigError(() -> ConfigValues.string(config().put("jql", 5), "jql"), "jql must be text");
  }

  @Test
  void blankStringIsAbsent() throws HarvesterException {
    assertThat(ConfigValues.string(config().put("email", "  "), "email")).isNull();
    assertThat(ConfigValues.string(config().put("email", " a@b.c "), "email")).isEqualTo("a@b.c");
    assertThat(ConfigValues.string(config(), "email")).isNull();
  }

  @Test
  void requiredStringNamesTheKey() {
    assertConfigError(() -> ConfigValues.requiredString(config(), "jql"), "jql is required");
  }

  @Test
  void integerOutsideRangeIsRejected() throws HarvesterException {
    assertConfigError(() -> ConfigValues.integer(config().put("sweepHours", 0), "sweepHours", 24, 1, 720),
      "sweepHours must be a whole number from 1 to 720");
    assertThat(ConfigValues.integer(config(), "sweepHours", 24, 1, 720)).isEqualTo(24);
  }

  @Test
  void flagDefaultsToFalse() throws HarvesterException {
    assertThat(ConfigValues.flag(config(), "allowPrivate")).isFalse();
    assertConfigError(() -> ConfigValues.flag(config().put("allowPrivate", "yes"), "allowPrivate"), "allowPrivate must be true or false");
  }

  @Test
  void choiceIsCaseInsensitiveAndListsOptions() throws HarvesterException {
    List<String> options = List.of("cloud", "datacenter", "auto");
    assertThat(ConfigValues.choice(config().put("deployment", "Cloud"), "deployment", "auto", options)).isEqualTo("cloud");
    assertThat(ConfigValues.choice(config(), "deployment", "auto", options)).isEqualTo("auto");
    assertConfigError(() -> ConfigValues.choice(config().put("deployment", "x"), "deployment", "auto", options),
      "cloud, datacenter, auto");
  }

  @Test
  void missingSecretIsRejected() throws HarvesterException {
    assertConfigError(() -> ConfigValues.requiredSecret(Map.of(), "token"), "the token secret is required");
    assertConfigError(() -> ConfigValues.requiredSecret(Map.of("token", " "), "token"), "the token secret is required");
    assertThat(ConfigValues.requiredSecret(Map.of("token", "t"), "token")).isEqualTo("t");
  }
}
