package dev.alvo.pieria.api.response;

import java.time.Instant;
import java.util.List;

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
  Instant createdAt,
  List<TemporalValidity> validity,
  List<RevisionResponse> assertions) {
  public MemoryResponse(String id, String type, String content, String topicKey, String sessionId,
                        boolean superseded, String payload, Instant createdAt) {
    this(id, type, content, topicKey, sessionId, superseded, payload, createdAt, List.of(), List.of());
  }

  public MemoryResponse withAssertions(List<RevisionResponse> history) {
    return new MemoryResponse(id, type, content, topicKey, sessionId, superseded, payload, createdAt, validity, history);
  }

  public MemoryResponse withValidity(List<TemporalValidity> segments) {
    return new MemoryResponse(id, type, content, topicKey, sessionId, superseded, payload, createdAt, segments, assertions);
  }
}
