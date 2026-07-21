package dev.alvo.pieria.api.request;

/** Optional filters for {@code GET /v1/profiles/{name}/audit}. */
public record AuditListRequest(
  String search,
  String operation,
  String client,
  String harness,
  String channel,
  String outcome,
  Integer status,
  String session,
  String taskId,
  String requestId,
  String from,
  String to,
  Boolean truncated,
  Integer limit,
  String cursor) {
}
