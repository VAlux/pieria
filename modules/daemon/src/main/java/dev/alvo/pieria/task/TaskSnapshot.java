package dev.alvo.pieria.task;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Immutable point-in-time view of an async task's progress, swapped atomically by the
 * {@link TaskRegistry} as the task advances. {@code startedAt}/{@code finishedAt} are kept for
 * server-side TTL eviction and are not part of the polling wire shape; {@code result} carries the
 * task's terminal payload (e.g. {@code {"count": n}} for ingest, the code-index summary for code).
 */
public record TaskSnapshot(
  TaskStatus status,
  String phase,
  int done,
  int total,
  Instant startedAt,
  Instant finishedAt,
  String errorKind,
  String errorMessage,
  JsonNode result) {

  static TaskSnapshot running(Instant now) {
    return new TaskSnapshot(TaskStatus.RUNNING, null, 0, 0, now, null, null, null, null);
  }

  /** A new RUNNING snapshot with updated phase counters; ignored once the task is terminal. */
  TaskSnapshot withProgress(String phase, int done, int total) {
    if (status != TaskStatus.RUNNING) {
      return this;
    }
    return new TaskSnapshot(TaskStatus.RUNNING, phase, done, total, startedAt, null, null, null, null);
  }

  TaskSnapshot succeeded(JsonNode result, Instant now) {
    return new TaskSnapshot(TaskStatus.SUCCEEDED, phase, done, total, startedAt, now, null, null, result);
  }

  TaskSnapshot failed(String errorKind, String errorMessage, Instant now) {
    return new TaskSnapshot(TaskStatus.FAILED, phase, done, total, startedAt, now, errorKind, errorMessage, null);
  }

  boolean isTerminal() {
    return status != TaskStatus.RUNNING;
  }
}
