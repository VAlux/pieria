package dev.alvo.pieria.config.toml;

import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.config.model.DaemonOverrides;
import dev.alvo.pieria.config.model.DaemonOverrides.Ingestion;
import dev.alvo.pieria.config.model.DaemonOverrides.Retrieval;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DaemonOverrides#isEmpty()} hand-lists every {@code Ingestion}/{@code Retrieval} component
 * it must check, and {@code ProfileConfigService.put} clears a profile's stored row whenever it
 * reports {@code true} — so a component missing from that list silently drops a profile's only
 * override while the console reports success. It already happened once: {@code
 * nearDuplicateThreshold} and {@code semanticDuplicateThreshold} were both missing.
 *
 * <p>Rather than re-deriving the same component list a third time (a whitelist copy already exists
 * in {@code ProfileConfigController} and {@code ConfigSchemaTests}), these tests set exactly one
 * record component at a time via reflection and assert {@code isEmpty()} sees it. A future
 * component added to either record without a matching {@code isEmpty()} update fails here instead
 * of shipping.
 */
class DaemonOverridesIsEmptyTests {

  @Test
  void bareOverridesIsEmpty() {
    assertThat(new DaemonOverrides(null, null).isEmpty()).isTrue();
  }

  /** The exact regression this class exists to catch. */
  @Test
  void nearDuplicateThresholdAloneIsNotEmpty() {
    DaemonOverrides overrides = new DaemonOverrides(null, retrievalWithOnly("nearDuplicateThreshold"));

    assertThat(overrides.isEmpty()).isFalse();
  }

  @Test
  void semanticDuplicateThresholdAloneIsNotEmpty() {
    DaemonOverrides overrides = new DaemonOverrides(null, retrievalWithOnly("semanticDuplicateThreshold"));

    assertThat(overrides.isEmpty()).isFalse();
  }

  @Test
  void everyIngestionComponentAloneIsNotEmpty() throws ReflectiveOperationException {
    for (RecordComponent component : Ingestion.class.getRecordComponents()) {
      Ingestion ingestion = construct(Ingestion.class, component.getName());
      assertThat(new DaemonOverrides(ingestion, null).isEmpty())
        .as("isEmpty() must be false when only Ingestion.%s is set — add it to the allNull(...) call",
          component.getName())
        .isFalse();
    }
  }

  @Test
  void everyRetrievalComponentAloneIsNotEmpty() throws ReflectiveOperationException {
    for (RecordComponent component : Retrieval.class.getRecordComponents()) {
      Retrieval retrieval = construct(Retrieval.class, component.getName());
      assertThat(new DaemonOverrides(null, retrieval).isEmpty())
        .as("isEmpty() must be false when only Retrieval.%s is set — add it to the allNull(...) call",
          component.getName())
        .isFalse();
    }
  }

  private static Retrieval retrievalWithOnly(String componentName) {
    try {
      return construct(Retrieval.class, componentName);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Builds {@code type} via its canonical constructor with only {@code componentName} non-null. */
  private static <T extends Record> T construct(Class<T> type, String componentName)
      throws ReflectiveOperationException {
    RecordComponent[] components = type.getRecordComponents();
    Class<?>[] paramTypes = new Class<?>[components.length];
    Object[] args = new Object[components.length];
    int targetIndex = -1;
    for (int i = 0; i < components.length; i++) {
      paramTypes[i] = components[i].getType();
      if (components[i].getName().equals(componentName)) targetIndex = i;
    }
    if (targetIndex < 0) {
      throw new IllegalArgumentException("no component named '" + componentName + "' on " + type);
    }
    args[targetIndex] = sampleValue(paramTypes[targetIndex]);

    Constructor<T> ctor = type.getDeclaredConstructor(paramTypes);
    return ctor.newInstance(args);
  }

  private static Object sampleValue(Class<?> type) {
    if (type == Integer.class) return 1;
    if (type == Double.class) return 1.0;
    if (type == Long.class) return 1L;
    if (type == Boolean.class) return Boolean.TRUE;
    if (type == String.class) return "x";
    if (type == RecallMode.class) return RecallMode.values()[0];
    throw new IllegalStateException(
      "no sample value wired up for " + type + " in this test — add one so the drift test covers it");
  }
}
