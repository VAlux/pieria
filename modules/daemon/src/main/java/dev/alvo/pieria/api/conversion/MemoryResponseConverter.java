package dev.alvo.pieria.api.conversion;

import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.domain.Memory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Maps the daemon's domain {@link Memory} into the shared {@link MemoryResponse} wire DTO.
 *
 * <p>This mapping lives in the daemon (not in the shared contract module) so the shared module
 * stays free of any dependency on the daemon's domain types. The gateway, which also depends on the
 * shared module, therefore never sees {@code Memory}.
 */
@Component
public final class MemoryResponseConverter implements Converter<Memory, MemoryResponse> {

  @Override
  public MemoryResponse convert(Memory memory) {
    return new MemoryResponse(
      memory.id(),
      memory.type() == null ? null : memory.type().wire(),
      memory.content(),
      memory.topicKey(),
      memory.sessionId(),
      memory.superseded(),
      memory.payload(),
      memory.createdAt());
  }
}
