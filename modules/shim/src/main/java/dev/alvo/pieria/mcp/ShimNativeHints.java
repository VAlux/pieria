package dev.alvo.pieria.mcp;

import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.RememberRequest;
import dev.alvo.pieria.api.response.ErrorResponse;
import dev.alvo.pieria.api.response.MemoryListResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.RecallResponse;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image reflection hints for the shared HTTP contract DTOs the shim serializes and
 * deserializes with Jackson 3 outside Spring MVC's controller binding pipeline.
 */
public class ShimNativeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    for (Class<?> type : contractTypes()) {
      hints.reflection().registerType(type,
        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
        MemberCategory.INVOKE_PUBLIC_METHODS);
    }
  }

  private static Class<?>[] contractTypes() {
    return new Class<?>[] {
      RecallRequest.class,
      RememberRequest.class,
      ErrorResponse.class,
      MemoryListResponse.class,
      MemoryResponse.class,
      RecallResponse.class,
      RecallResponse.RecallDebug.class,
      RecallResponse.RecallDebug.Provenance.class,
      RecallResponse.RecallDebug.ChannelDiagnostic.class
    };
  }
}
