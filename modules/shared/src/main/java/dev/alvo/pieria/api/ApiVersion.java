package dev.alvo.pieria.api;

/**
 * Version of the daemon's HTTP contract, reported by {@code /pieria-status}. The web console is
 * shipped as a separate directory and compares this with the version it was built against, so a
 * console swapped independently of the daemon says so instead of calling endpoints that changed.
 * Bump it when an endpoint the console relies on changes shape incompatibly.
 */
public final class ApiVersion {

  public static final int CURRENT = 2;

  private ApiVersion() {
  }
}
