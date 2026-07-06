package dev.alvo.pieria.cli.modules.init;

/**
 * Result of a cheap pre-flight reachability check against the daemon ({@code GET /pieria-health}):
 * whether the daemon answered at all, independent of whether the subsequent real call succeeds.
 * Shared by the onboarding and config CLI clients so "daemon down" is distinguished from a real
 * error uniformly.
 */
public enum Reachability {
  OK,
  DAEMON_DOWN
}
