package dev.alvo.pieria.api.response;

import tools.jackson.databind.JsonNode;

/**
 * Progress of an async daemon task, returned by {@code GET /v1/tasks/{taskId}}. While
 * {@code status} is {@code RUNNING}, {@code phase}/{@code done}/{@code total} drive a progress bar;
 * on {@code SUCCEEDED}, {@code result} carries the task's payload (e.g. {@code {"count": n}} for
 * ingest, the code-index summary for code); on {@code FAILED}, {@code errorKind}/{@code errorMessage}
 * explain the failure.
 */
public record TaskStatusResponse(
  String status,
  String phase,
  int done,
  int total,
  String errorKind,
  String errorMessage,
  JsonNode result) {
}
