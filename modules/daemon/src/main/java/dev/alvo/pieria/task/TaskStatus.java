package dev.alvo.pieria.task;

/**
 * Lifecycle of an async daemon task. {@link #RUNNING} is non-terminal; {@link #SUCCEEDED},
 * {@link #FAILED} and {@link #CANCELLED} are terminal and a polling client stops once it observes
 * any of them. {@link #CANCELLED} is the result of an explicit {@code kill} request.
 */
public enum TaskStatus {
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED
}
