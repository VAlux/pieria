package dev.alvo.pieria.audit;

import java.time.Instant;

/** Validated server-side filters for a profile's audit history. */
public record AuditQuery(
  String text,
  String operation,
  String client,
  String harness,
  String channel,
  String outcome,
  Integer status,
  String sessionId,
  String taskId,
  String requestId,
  Instant from,
  Instant to,
  Boolean truncated,
  Instant cursorTime,
  String cursorId,
  int limit) {
}
