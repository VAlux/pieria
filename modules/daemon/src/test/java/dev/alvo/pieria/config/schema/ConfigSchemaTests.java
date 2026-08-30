package dev.alvo.pieria.config.schema;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.config.model.DaemonOverrides;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schema carries human copy; the KEYS must stay in lockstep with DaemonOverrides, or the
 * console would offer a field the daemon rejects (or silently hide one it accepts).
 */
class ConfigSchemaTests {

  private static final String TRACE_PREFIX = "pieria.ingestion.trace.";

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

  // The global scope has no DaemonOverrides to check against, so a mistyped global key would write
  // to pieria.properties and bind to nothing — silently. TraceProperties is a record with the same
  // kebab-case binding rule, so its components can be checked the same way the profile keys are.
  @Test
  void traceKeysMatchTracePropertiesExactly() {
    Set<String> fromCode = kebabComponentNames(TraceProperties.class).stream()
      .map(name -> TRACE_PREFIX + name)
      .collect(Collectors.toCollection(LinkedHashSet::new));

    Set<String> fromSchema = schema.forScope("global").stream()
      .map(ConfigField::key)
      .filter(key -> key.startsWith(TRACE_PREFIX))
      .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(fromSchema).containsExactlyInAnyOrderElementsOf(fromCode);
  }

  // The kinds drive both GlobalConfigService's validation and the control the console renders, so a
  // numeric property declared "string" would accept "banana" and write it to the file the daemon
  // boots from.
  @Test
  void traceKeyKindsMatchTheirDeclaredJavaTypes() {
    Map<String, String> expected = new LinkedHashMap<>();
    for (RecordComponent component : TraceProperties.class.getRecordComponents()) {
      expected.put(TRACE_PREFIX + toKebab(component.getName()), kindFor(component.getType()));
    }

    assertThat(schema.forScope("global"))
      .filteredOn(field -> field.key().startsWith(TRACE_PREFIX))
      .allSatisfy(field ->
        assertThat(field.kind()).as(field.key()).isEqualTo(expected.get(field.key())));
  }

  /** The console kind each Java type must be declared as. */
  private static String kindFor(Class<?> type) {
    if (type == boolean.class) {
      return "bool";
    }
    if (type == int.class || type == long.class) {
      return "int";
    }
    if (type == double.class) {
      return "double";
    }
    // A List<String> binds from the comma-separated form a properties file can hold.
    return "string";
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
