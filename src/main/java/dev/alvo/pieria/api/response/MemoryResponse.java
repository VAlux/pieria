package dev.alvo.pieria.api.response;

import dev.alvo.pieria.domain.Memory;

import java.time.Instant;

/**
 * Outward shape of a {@link Memory}. The {@code type} is the canonical wire string; internal
 * profile ids are never exposed (the memory's own content-addressed id is fine).
 */
public record MemoryResponse(
  String id,
  String type,
  String content,
  String topicKey,
  String sessionId,
  boolean superseded,
  String payload,
  Instant createdAt) {

  public static MemoryResponse from(Memory memory) {
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
