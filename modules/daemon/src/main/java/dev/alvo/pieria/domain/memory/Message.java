package dev.alvo.pieria.domain.memory;

import dev.alvo.pieria.domain.ContentId;

import java.time.Instant;

/**
 * A single conversation message. {@code id} is content-addressed
 * ({@link ContentId#forMessage(String, String, String, String)}) so re-ingesting the same transcript
 * into one profile is idempotent while identical transcripts can coexist across profiles.
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
