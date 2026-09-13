package dev.alvo.pieria.domain.memory;

import dev.alvo.pieria.domain.temporal.ConflictDecision;

import java.time.Instant;

/**
 * A single extracted unit of knowledge. Fields beyond {@code type}/{@code content}
 * are populated progressively: {@code topicKey}/{@code supersedes}/{@code embedText} arrive in
 * . {@code id} and {@code createdAt} are assigned at store time when null.
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
  Instant createdAt,
  Instant validFrom,
  Instant validTo,
  String sourceOccurrence,
  ConflictDecision conflict,
  boolean semanticChecked) {

  public Memory withConflict(ConflictDecision decision) {
    return new Memory(id, sessionId, type, content, topicKey, supersedes, superseded, payload, embedText, createdAt, validFrom, validTo, sourceOccurrence, decision, true);
  }

  public Memory(String id, String sessionId, MemoryType type, String content, String topicKey,
                String supersedes, boolean superseded, String payload, String embedText, Instant createdAt) {
    this(id, sessionId, type, content, topicKey, supersedes, superseded, payload, embedText, createdAt, null, null, null, null, false);
  }

  public Memory withValidity(Instant from, Instant to, String occurrence) {
    if (from != null && to != null && !from.isBefore(to)) throw new IllegalArgumentException("validFrom must precede validTo");
    return new Memory(id, sessionId, type, content, topicKey, supersedes, superseded, payload, embedText, createdAt, from, to, occurrence, conflict, semanticChecked);
  }


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
