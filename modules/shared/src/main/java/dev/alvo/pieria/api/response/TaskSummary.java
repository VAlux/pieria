package dev.alvo.pieria.api.response;

/**
 * One row of {@link TaskListResponse}: an async task's id, display metadata and current progress.
 * {@code startedAtEpochMs}/{@code phaseStartedAtEpochMs} are epoch millis ({@code 0} when absent) so
 * a client can compute elapsed time and a per-phase ETA without depending on jsr310 serialization.
 */
public record TaskSummary(
  String id,
  String kind,
  String profile,
  String status,
  String phase,
  int done,
  int total,
  long startedAtEpochMs,
  long phaseStartedAtEpochMs,
  String errorKind,
  String errorMessage) {
}
