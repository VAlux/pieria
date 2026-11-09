package dev.alvo.pieria.harvest;

/** How a harvest run ended, or {@link #RUNNING} while it is going. */
public enum RunStatus {
  RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED
}
