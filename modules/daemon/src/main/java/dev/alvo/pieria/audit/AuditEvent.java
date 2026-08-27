package dev.alvo.pieria.audit;

import java.time.Instant;

/**
 * One immutable profile audit event.
 */
public record AuditEvent(
  String id,
  String profileId,
  String profileName,
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
  String clientVersion,
  String serverVersion,
  String remoteAddress,
  String method,
  String path,
  String queryString,
  String requestMediaType,
  String responseMediaType,
  Instant startedAt,
  Instant completedAt,
  long durationMs,
  Integer httpStatus,
  String outcome,
  String errorKind,
  String errorMessage,
  String metadata,
  String requestBody,
  long requestBytes,
  String requestSha256,
  boolean requestTruncated,
  String responseBody,
  long responseBytes,
  String responseSha256,
  boolean responseTruncated) {
}
