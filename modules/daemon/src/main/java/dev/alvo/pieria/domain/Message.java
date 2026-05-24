package dev.alvo.pieria.domain;

import java.time.Instant;

/**
 * A single conversation message. {@code id} is content-addressed
 * ({@link ContentId#forMessage}) so re-ingesting the same transcript is idempotent.
 * {@code id}/{@code createdAt} may be null on inbound messages and are assigned at store time.
 */
public record Message(
  String id,
  String sessionId,
  String role,
  String content,
  Instant createdAt) {

  /**
   * Convenience for inbound messages that do not yet have an id or timestamp.
   */
  public static Message of(String sessionId, String role, String content) {
    return new Message(null, sessionId, role, content, null);
  }
}
