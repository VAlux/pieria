package dev.alvo.pieria.api.response;

import tools.jackson.databind.JsonNode;

/**
 * Progress of an async daemon task, returned by {@code GET /v1/tasks/{taskId}}. While
 * {@code status} is {@code RUNNING}, {@code phase}/{@code done}/{@code total} drive a progress bar;
 * on {@code SUCCEEDED}, {@code result} carries the task's payload (e.g. {@code {"count": n}} for
 * ingest, the code-index summary for code); on {@code FAILED} or {@code CANCELLED},
 * {@code errorKind}/{@code errorMessage} explain the outcome.
 *
 * <p>{@code kind}/{@code profile} identify the task for display; {@code startedAtEpochMs} and
 * {@code phaseStartedAtEpochMs} (epoch millis, {@code 0} when absent) let a re-attaching client
 * compute an ETA from server-side timing. Timestamps are sent as epoch-millis rather than
 * {@code Instant} because jsr310 is not guaranteed to be registered on the daemon's mapper.
 */
public record TaskStatusResponse(
  String status,
  String kind,
  String profile,
  String phase,
  int done,
  int total,
  long startedAtEpochMs,
  long phaseStartedAtEpochMs,
  String errorKind,
  String errorMessage,
  JsonNode result) {
}
