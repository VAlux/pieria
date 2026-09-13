package dev.alvo.pieria.storage;

/** Optimistic revision precondition failed; callers must fetch history before retrying. */
public final class TemporalConflictException extends RuntimeException {
  public TemporalConflictException() { super("Assertion revision changed; fetch history and retry with the current revision ID"); }
}
