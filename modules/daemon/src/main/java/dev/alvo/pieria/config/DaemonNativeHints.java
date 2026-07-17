package dev.alvo.pieria.config;

import dev.alvo.pieria.code.PieriaTreeSitterLibraryLookup;
import dev.alvo.pieria.config.model.DaemonOverrides;
import dev.alvo.pieria.config.model.DiscoveryConfig;
import dev.alvo.pieria.config.model.PieriaConfigFile;
import dev.alvo.pieria.onboarding.OnboardResult;
import dev.alvo.pieria.onboarding.OnboardPlanResult;
import dev.alvo.pieria.reminiscence.ReminiscenceResult;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Native-image reflection hints for daemon-specific reflective types: the TOML-bound config model,
 * Spring AI structured-output DTOs, and the Azure provider types.
 *
 * <p>The shared {@code api.request}/{@code api.response} DTOs are <em>not</em> registered here — they
 * are auto-discovered for every native binary by {@code dev.alvo.pieria.api.ApiContractFeature}
 * (wired via {@code --features=} in each module's build), so they need no hand-maintained list.
 */
public class DaemonNativeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    for (Class<?> type : configModelTypes()) {
      hints.reflection().registerType(type,
        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
        MemberCategory.INVOKE_PUBLIC_METHODS);
    }
    // Async task result payloads serialized to JSON via ObjectMapper.valueToTree inside the task body
    // (OnboardController / ReminiscenceController). Unlike CodeIndexResponse — an api.response DTO
    // auto-registered by ApiContractFeature — these live in daemon domain packages, so without a hint
    // Jackson cannot reflect over their record accessors: valueToTree throws
    // MissingReflectionRegistrationError, which (escaping into the task's unread Future) strands the
    // task RUNNING forever. Register the public accessors/constructors Jackson serializes over.
    for (Class<?> type : taskResultTypes()) {
      hints.reflection().registerType(type,
        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
        MemberCategory.INVOKE_PUBLIC_METHODS);
    }
    // Structured-output DTOs are private records nested in OpenAiModelGateway, parsed from model
    // JSON by Spring AI's BeanOutputConverter (Jackson). They are registered by binary name because
    // they are not visible here; declared constructors reach the private canonical constructor and
    // public methods reach the record accessors that Jackson reflects over.
    for (String type : modelGatewayDtoTypes()) {
      hints.reflection().registerType(TypeReference.of(type),
        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
        MemberCategory.INVOKE_PUBLIC_METHODS);
    }
    // Azure OpenAI / Microsoft Foundry types reached reflectively when pieria.provider.type=azure:
    // the EnvironmentPostProcessor + ConfigurationPropertiesBinding converter bind the api service
    // version, and the OpenAI SDK builds the Azure client from an API-key credential. Registered by
    // binary name so the OpenAI SDK need not be on the AOT compile path of this registrar. The SDK
    // ships some of its own hints; extend this list as nativeCompile surfaces gaps.
    for (String type : azureProviderTypes()) {
      hints.reflection().registerType(TypeReference.of(type),
        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
        MemberCategory.INVOKE_PUBLIC_METHODS);
    }
    // OpenAI SDK error-body model, deserialized by com.openai.core.handlers.ErrorHandler on any
    // non-2xx provider response (400/422/429/5xx). Its @JsonCreator constructor is non-public and its
    // @JsonAnySetter (putAdditionalProperty) is a *private* method, so INVOKE_PUBLIC_* cannot reach
    // them — Jackson's reflective invoke then throws MissingReflectionRegistrationError. Because that
    // is an Error (not an Exception), the SDK's `catch (Exception)` in errorBodyHandler does NOT catch
    // it: it escapes and masks the real HTTP error as an opaque "extraction failed". Register declared
    // members so the true provider error surfaces (as the SDK's typed RateLimit/BadRequest/… exception)
    // instead of crashing the call.
    for (String type : openAiErrorModelTypes()) {
      hints.reflection().registerType(TypeReference.of(type),
        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
        MemberCategory.INVOKE_DECLARED_METHODS);
    }
    // The Tree-sitter NativeLibraryLookup SPI is instantiated reflectively by jtreesitter's
    // ServiceLoader at runtime (to point it at the bundled libtree-sitter core); register its
    // constructor so the lookup survives in the native image.
    hints.reflection().registerType(PieriaTreeSitterLibraryLookup.class,
      MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
  }

  private static String[] azureProviderTypes() {
    return new String[] {
      "com.openai.azure.AzureOpenAIServiceVersion",
      "com.openai.azure.AzureOpenAIServiceVersion$Companion",
      "com.openai.azure.credential.AzureApiKeyCredential",
      "com.openai.credential.Credential",
      "dev.alvo.pieria.config.ProviderEnvironmentPostProcessor"
    };
  }

  private static String[] openAiErrorModelTypes() {
    return new String[] {
      "com.openai.models.ErrorObject"
    };
  }

  private static String[] modelGatewayDtoTypes() {
    String owner = "dev.alvo.pieria.model.OpenAiModelGateway$";
    return new String[] {
      owner + "UnifiedCandidateDto",
      owner + "UnifiedCandidateList",
      owner + "VerificationDto",
      owner + "VerificationItemDto",
      owner + "BatchVerificationDto",
      owner + "ClassificationDto",
      owner + "ClassificationItemDto",
      owner + "BatchClassificationDto",
      owner + "QueryAnalysisDto",
      owner + "GraphDto",
      owner + "GraphItemDto",
      owner + "BatchGraphDto",
      owner + "EntityDto",
      owner + "TripleDto"
    };
  }

  private static Class<?>[] configModelTypes() {
    return new Class<?>[] {
      DaemonOverrides.class,
      DaemonOverrides.Ingestion.class,
      DaemonOverrides.Retrieval.class,
      PieriaConfigFile.class,
      DiscoveryConfig.class
    };
  }

  private static Class<?>[] taskResultTypes() {
    return new Class<?>[] {
      OnboardResult.class,
      OnboardPlanResult.class,
      ReminiscenceResult.class
    };
  }
}
