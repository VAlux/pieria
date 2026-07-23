package dev.alvo.pieria.api.response;

import java.util.List;

/**
 * One row of {@link TaskListResponse}: an async task's id, display metadata and current progress.
 * The ordered lane snapshots are the only progress representation; {@code startedAtEpochMs} is an
 * epoch millis value so clients do not depend on jsr310 serialization.
 */
public record TaskSummary(
  String id,
  String kind,
  String profile,
  String status,
  List<TaskLaneProgress> lanes,
  long startedAtEpochMs,
  String errorKind,
  String errorMessage) {
}
