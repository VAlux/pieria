package dev.alvo.pieria.config;

import dev.alvo.pieria.config.model.DaemonOverrides;
import dev.alvo.pieria.config.model.PieriaConfigFile;
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

  private static String[] modelGatewayDtoTypes() {
    String owner = "dev.alvo.pieria.model.OpenAiModelGateway$";
    return new String[] {
      owner + "ExtractedMemory",
      owner + "ExtractionResult",
      owner + "RawCandidate",
      owner + "CandidateList",
      owner + "VerificationDto",
      owner + "ClassificationDto",
      owner + "QueryAnalysisDto",
      owner + "GraphDto",
      owner + "EntityDto",
      owner + "TripleDto"
    };
  }

  private static Class<?>[] configModelTypes() {
    return new Class<?>[] {
      DaemonOverrides.class,
      DaemonOverrides.Ingestion.class,
      DaemonOverrides.Retrieval.class,
      PieriaConfigFile.class
    };
  }
}
