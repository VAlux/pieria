package dev.alvo.pieria.api.response;

import java.time.Instant;

/** Compact audit row returned by the paginated list endpoint. */
public record AuditEventSummary(
  String id,
  String eventType,
  String operation,
  String requestId,
  String parentRequestId,
  String taskId,
  String sessionId,
  String resourceId,
  String client,
  String harness,
  String channel,
  Instant startedAt,
  Instant completedAt,
  long durationMs,
  Integer httpStatus,
  String outcome,
  String errorKind,
  String errorMessage,
  long requestBytes,
  boolean requestTruncated,
  long responseBytes,
  boolean responseTruncated,
  String responsePreview) {
}
