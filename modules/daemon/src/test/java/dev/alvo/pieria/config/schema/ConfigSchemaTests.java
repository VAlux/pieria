package dev.alvo.pieria.config.schema;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import dev.alvo.pieria.config.model.DaemonOverrides;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schema carries human copy; the KEYS must stay in lockstep with DaemonOverrides, or the
 * console would offer a field the daemon rejects (or silently hide one it accepts).
 */
class ConfigSchemaTests {

  private final ConfigSchemaService schema = new ConfigSchemaService();

  @Test
  void profileScopedKeysMatchDaemonOverridesExactly() {
    Set<String> fromCode = new LinkedHashSet<>();
    kebabComponentNames(DaemonOverrides.Ingestion.class)
      .forEach(name -> fromCode.add("ingestion." + name));
    kebabComponentNames(DaemonOverrides.Retrieval.class)
      .forEach(name -> fromCode.add("retrieval." + name));

    Set<String> fromSchema = schema.forScope("profile").stream()
      .map(ConfigField::key)
      .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(fromSchema).containsExactlyInAnyOrderElementsOf(fromCode);
  }

  @Test
  void everyFieldDeclaresAKnownScopeTierAndKind() {
    assertThat(schema.all()).isNotEmpty();
    assertThat(schema.all()).allSatisfy(field -> {
      assertThat(field.scope()).isIn("profile", "global");
      assertThat(field.tier()).isIn("live", "restart", "locked");
      assertThat(field.kind()).isIn("weight", "int", "double", "bool", "enum", "string", "secret");
      assertThat(field.label()).isNotBlank();
      if ("enum".equals(field.kind())) {
        assertThat(field.options()).isNotEmpty();
      }
    });
  }

  @Test
  void profileFieldsAreAlwaysLiveBecauseTheResolverInvalidatesOnWrite() {
    assertThat(schema.forScope("profile")).allSatisfy(
      field -> assertThat(field.tier()).isEqualTo("live"));
  }

  @Test
  void embeddingDimensionAndDatabasePathAreLocked() {
    assertThat(schema.find("pieria.model.embedding-dimension"))
      .get().extracting(ConfigField::tier).isEqualTo("locked");
    assertThat(schema.find("pieria.db.path"))
      .get().extracting(ConfigField::tier).isEqualTo("locked");
  }

  @Test
  void noGlobalFieldClaimsToApplyWithoutARestart() {
    // spring.config.import reads pieria.properties at startup and never again, so a global key
    // cannot take effect until the daemon restarts. A "live" global tier would tell the operator
    // their change was already in force when it was not. Per-profile keys are genuinely live —
    // EffectiveConfigResolver invalidates its cache on write — which is why they keep that tier.
    assertThat(schema.forScope("global")).allSatisfy(
      field -> assertThat(field.tier()).isIn("restart", "locked"));
  }

  private static Set<String> kebabComponentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent component : type.getRecordComponents()) {
      names.add(toKebab(component.getName()));
    }
    return names;
  }

  private static String toKebab(String camel) {
    StringBuilder sb = new StringBuilder(camel.length() + 4);
    for (char character : camel.toCharArray()) {
      if (Character.isUpperCase(character)) {
        sb.append('-').append(Character.toLowerCase(character));
      } else {
        sb.append(character);
      }
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }
}
