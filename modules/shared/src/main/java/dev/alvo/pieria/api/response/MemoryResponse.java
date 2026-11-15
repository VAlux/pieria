package dev.alvo.pieria.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Outward shape of a memory. The {@code type} is the canonical wire string; internal
 * profile ids are never exposed (the memory's own content-addressed id is fine). {@code profile}
 * names the profile that owns the memory; it is set only on a recall that read a profile together
 * with the profiles below it, and omitted otherwise.
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
  List<RevisionResponse> assertions,
  @JsonInclude(JsonInclude.Include.NON_NULL) MemoryOrigin origin,
  @JsonInclude(JsonInclude.Include.NON_NULL) String profile) {

  public MemoryResponse(String id, String type, String content, String topicKey, String sessionId,
                        boolean superseded, String payload, Instant createdAt,
                        List<TemporalValidity> validity, List<RevisionResponse> assertions, MemoryOrigin origin) {
    this(id, type, content, topicKey, sessionId, superseded, payload, createdAt, validity, assertions, origin, null);
  }

  public MemoryResponse(String id, String type, String content, String topicKey, String sessionId,
                        boolean superseded, String payload, Instant createdAt,
                        List<TemporalValidity> validity, List<RevisionResponse> assertions) {
    this(id, type, content, topicKey, sessionId, superseded, payload, createdAt, validity, assertions, null);
  }

  public MemoryResponse(String id, String type, String content, String topicKey, String sessionId,
                        boolean superseded, String payload, Instant createdAt) {
    this(id, type, content, topicKey, sessionId, superseded, payload, createdAt, List.of(), List.of(), null);
  }

  public MemoryResponse withOrigin(MemoryOrigin memoryOrigin) {
    return new MemoryResponse(id, type, content, topicKey, sessionId, superseded, payload, createdAt, validity, assertions, memoryOrigin, profile);
  }

  public MemoryResponse withAssertions(List<RevisionResponse> history) {
    return new MemoryResponse(id, type, content, topicKey, sessionId, superseded, payload, createdAt, validity, history, origin, profile);
  }

  public MemoryResponse withValidity(List<TemporalValidity> segments) {
    return new MemoryResponse(id, type, content, topicKey, sessionId, superseded, payload, createdAt, segments, assertions, origin, profile);
  }

  /** This memory attributed to the profile that owns it. */
  public MemoryResponse withProfile(String owner) {
    return new MemoryResponse(id, type, content, topicKey, sessionId, superseded, payload, createdAt, validity, assertions, origin, owner);
  }
}
