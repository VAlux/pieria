package dev.alvo.pieria.config;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.RememberRequest;
import dev.alvo.pieria.api.response.ErrorResponse;
import dev.alvo.pieria.api.response.HealthResponse;
import dev.alvo.pieria.api.response.IngestResponse;
import dev.alvo.pieria.api.response.MemoryListResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.RecallResponse;
import dev.alvo.pieria.api.response.StatusResponse;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Native-image reflection hints for shared API records used by Spring MVC and manual Jackson calls.
 */
public class DaemonNativeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    for (Class<?> type : contractTypes()) {
      hints.reflection().registerType(type,
        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
        MemberCategory.INVOKE_PUBLIC_METHODS);
    }
    // Structured-output DTOs are private records nested in OllamaModelGateway, parsed from model
    // JSON by Spring AI's BeanOutputConverter (Jackson). They are registered by binary name because
    // they are not visible here; declared constructors reach the private canonical constructor and
    // public methods reach the record accessors that Jackson reflects over.
    for (String type : modelGatewayDtoTypes()) {
      hints.reflection().registerType(TypeReference.of(type),
        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
        MemberCategory.INVOKE_PUBLIC_METHODS);
    }
  }

  private static String[] modelGatewayDtoTypes() {
    String owner = "dev.alvo.pieria.model.OllamaModelGateway$";
    return new String[] {
      owner + "ExtractedMemory",
      owner + "ExtractionResult",
      owner + "RawCandidate",
      owner + "CandidateList",
      owner + "VerificationDto",
      owner + "ClassificationDto",
      owner + "QueryAnalysisDto"
    };
  }

  private static Class<?>[] contractTypes() {
    return new Class<?>[] {
      IngestRequest.class,
      IngestRequest.MessageDto.class,
      RecallRequest.class,
      RememberRequest.class,
      ErrorResponse.class,
      HealthResponse.class,
      IngestResponse.class,
      MemoryListResponse.class,
      MemoryResponse.class,
      RecallResponse.class,
      RecallResponse.RecallDebug.class,
      RecallResponse.RecallDebug.Provenance.class,
      RecallResponse.RecallDebug.ChannelDiagnostic.class,
      StatusResponse.class,
      StatusResponse.Setup.class
    };
  }
}
