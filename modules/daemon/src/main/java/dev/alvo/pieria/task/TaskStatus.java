package dev.alvo.pieria.task;

/**
 * Lifecycle of an async daemon task. {@link #RUNNING} is non-terminal; {@link #SUCCEEDED} and
 * {@link #FAILED} are terminal and a polling client stops once it observes either.
 */
public enum TaskStatus {
  RUNNING,
  SUCCEEDED,
  FAILED
}
