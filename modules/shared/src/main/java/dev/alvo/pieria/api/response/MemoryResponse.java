package dev.alvo.pieria.api.response;

import java.time.Instant;

/**
 * Outward shape of a memory. The {@code type} is the canonical wire string; internal
 * profile ids are never exposed (the memory's own content-addressed id is fine).
 *
 * <p>This DTO lives in the shared HTTP contract module and intentionally has no dependency on the
 * daemon's domain types. The daemon maps its {@code Memory} into this shape via its own
 * {@code dev.alvo.pieria.api.MemoryResponses} helper.
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
}
