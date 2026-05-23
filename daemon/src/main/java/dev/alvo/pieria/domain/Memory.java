package dev.alvo.pieria.domain;

import java.time.Instant;

/**
 * A single extracted unit of knowledge (SPEC 5.1). Fields beyond {@code type}/{@code content}
 * are populated progressively: {@code topicKey}/{@code supersedes}/{@code embedText} arrive in
 * Phase 2. {@code id} and {@code createdAt} are assigned at store time when null.
 *
 * @param payload heterogeneous per-type fields as a JSON string (defaults to {@code "{}"})
 */
public record Memory(
  String id,
  String sessionId,
  MemoryType type,
  String content,
  String topicKey,
  String supersedes,
  boolean superseded,
  String payload,
  String embedText,
  Instant createdAt) {

  /**
   * A freshly extracted/remembered memory with no id, version chain, or embedding yet.
   */
  public static Memory of(MemoryType type,
                          String content,
                          String sessionId,
                          String topicKey,
                          String payload) {
    return new Memory(
      null,
      sessionId,
      type,
      content,
      topicKey,
      null,
      false,
      payload == null ? "{}" : payload,
      null,
      null);
  }
}
