package dev.alvo.pieria.task;

/**
 * Thrown from a task's progress listener when a {@code kill} request has set the cancel flag, so the
 * work unwinds at the next phase tick. The {@link TaskRegistry} catches it and records a
 * {@link TaskStatus#CANCELLED} snapshot. Cancellation is cooperative and best-effort: the task stops
 * at the next checkpoint (or when an in-flight model call returns), and any memories already stored
 * before that point remain.
 */
public class TaskCancelledException extends RuntimeException {

  public TaskCancelledException() {
    super("task cancelled");
  }
}
