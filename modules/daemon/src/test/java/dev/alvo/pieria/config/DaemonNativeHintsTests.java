package dev.alvo.pieria.config;

import dev.alvo.pieria.model.OpenAiModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against native-image regressions: every structured-output record nested in
 * {@link OpenAiModelGateway} is parsed from model JSON by reflection (Jackson via Spring AI's
 * BeanOutputConverter), so each must carry a reflection hint or the native daemon fails at runtime
 * with {@code UnsupportedFeatureError: Record components not available}. This runs on the JVM (where
 * reflection always works), catching a missing hint at test time instead of in the native binary.
 */
class DaemonNativeHintsTests {

  @Test
  void everyStructuredOutputRecordIsRegisteredForNativeReflection() {
    RuntimeHints hints = new RuntimeHints();
    new DaemonNativeHints().registerHints(hints, getClass().getClassLoader());

    for (Class<?> nested : OpenAiModelGateway.class.getDeclaredClasses()) {
      if (nested.isRecord()) {
        assertThat(RuntimeHintsPredicates.reflection().onType(nested).test(hints))
          .as("missing native reflection hint for %s — add it to "
            + "DaemonNativeHints.modelGatewayDtoTypes()", nested.getName())
          .isTrue();
      }
    }
  }

  /**
   * The OpenAI SDK deserializes non-2xx provider bodies into {@code com.openai.models.ErrorObject}
   * via a non-public {@code @JsonCreator} and a private {@code @JsonAnySetter}. Without declared-member
   * hints the native daemon throws {@code MissingReflectionRegistrationError} (an {@code Error}, so the
   * SDK's {@code catch (Exception)} misses it) on every provider error, masking the real HTTP failure.
   */
  @Test
  void openAiErrorBodyModelIsRegisteredWithDeclaredMembers() {
    RuntimeHints hints = new RuntimeHints();
    new DaemonNativeHints().registerHints(hints, getClass().getClassLoader());

    TypeReference errorObject = TypeReference.of("com.openai.models.ErrorObject");
    assertThat(RuntimeHintsPredicates.reflection()
      .onType(errorObject)
      .withMemberCategories(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_DECLARED_METHODS)
      .test(hints))
      .as("com.openai.models.ErrorObject needs declared-constructor + declared-method hints so the "
        + "SDK can parse provider error bodies in the native daemon")
      .isTrue();
  }
}
