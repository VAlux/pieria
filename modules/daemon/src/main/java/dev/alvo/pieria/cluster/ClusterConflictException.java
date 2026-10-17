package dev.alvo.pieria.cluster;

/** Optimistic revision or source fingerprint conflict. */
public class ClusterConflictException extends RuntimeException {
  public ClusterConflictException(String message) { super(message); }
}
