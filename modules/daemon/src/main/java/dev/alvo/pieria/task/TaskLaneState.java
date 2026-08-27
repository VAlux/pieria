package dev.alvo.pieria.task;

/**
 * Lifecycle of one named lane within an asynchronous task.
 */
public enum TaskLaneState {
  QUEUED,
  RUNNING,
  WAITING,
  COMPLETED,
  FAILED,
  CANCELLED;

  boolean terminal() {
    return this == COMPLETED || this == FAILED || this == CANCELLED;
  }
}
