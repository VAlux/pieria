package dev.alvo.pieria.task;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable point-in-time view of an async task's progress, swapped atomically by the
 * {@link TaskRegistry} as the task advances. {@code startedAt}/{@code finishedAt} are kept for
 * server-side TTL eviction; each lane records when its current phase was first observed so a client
 * can compute an ETA from server-side timing even after re-attaching. {@code result}
 * carries the task's terminal payload (e.g. {@code {"count": n}} for ingest, the code-index summary
 * for code).
 */
public record TaskSnapshot(
  TaskStatus status,
  List<TaskLaneSnapshot> lanes,
  Instant startedAt,
  Instant finishedAt,
  String errorKind,
  String errorMessage,
  JsonNode result) {

  static TaskSnapshot running(Instant now) {
    return new TaskSnapshot(TaskStatus.RUNNING, List.of(), now, null, null, null, null);
  }

  /**
   * Add a queued lane while preserving first-creation order; ignored once the task is terminal.
   */
  TaskSnapshot addLane(String name) {
    if (status != TaskStatus.RUNNING) {
      return this;
    }
    if (lanes.stream().anyMatch(lane -> lane.name().equals(name))) {
      return this;
    }
    List<TaskLaneSnapshot> updated = new ArrayList<>(lanes);
    updated.add(new TaskLaneSnapshot(name, TaskLaneState.QUEUED, null, 0, 0, null));
    return new TaskSnapshot(status, List.copyOf(updated), startedAt, finishedAt,
      errorKind, errorMessage, result);
  }

  TaskSnapshot updateLane(String name, TaskLaneState state, String phase, int done, int total) {
    if (status != TaskStatus.RUNNING) {
      return this;
    }
    List<TaskLaneSnapshot> updated = lanes.stream()
      .map(lane -> lane.name().equals(name) ? lane.withState(state, phase, done, total) : lane)
      .toList();
    return new TaskSnapshot(status, updated, startedAt, finishedAt, errorKind, errorMessage, result);
  }

  TaskSnapshot succeeded(JsonNode result, Instant now) {
    if (isTerminal()) {
      return this;
    }
    return terminal(TaskStatus.SUCCEEDED, TaskLaneState.COMPLETED, null, null, result, now);
  }

  TaskSnapshot failed(String errorKind, String errorMessage, Instant now) {
    if (isTerminal()) {
      return this;
    }
    return terminal(TaskStatus.FAILED, TaskLaneState.FAILED, errorKind, errorMessage, null, now);
  }

  TaskSnapshot cancelled(Instant now) {
    if (isTerminal()) {
      return this;
    }
    return terminal(TaskStatus.CANCELLED, TaskLaneState.CANCELLED,
      "cancelled", "task cancelled by user", null, now);
  }

  private TaskSnapshot terminal(TaskStatus taskStatus, TaskLaneState laneState,
                                String kind, String message, JsonNode terminalResult, Instant now) {
    List<TaskLaneSnapshot> normalized = lanes.stream().map(lane -> lane.terminal(laneState)).toList();
    return new TaskSnapshot(taskStatus, normalized, startedAt, now, kind, message, terminalResult);
  }

  boolean isTerminal() {
    return status != TaskStatus.RUNNING;
  }
}
