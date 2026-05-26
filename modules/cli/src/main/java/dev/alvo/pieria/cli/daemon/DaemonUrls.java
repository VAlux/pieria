package dev.alvo.pieria.cli.daemon;

/**
 * Resolves the daemon base URL the same way across all CLI commands:
 * explicit option → {@code $PIERIA_DAEMON_URL} → localhost default.
 */
public final class DaemonUrls {

  public static final String DEFAULT_DAEMON_URL = "http://127.0.0.1:8077";

  private DaemonUrls() {
  }

  public static String resolve(String override) {
    if (override != null && !override.isBlank()) {
      return override;
    }
    return System.getenv().getOrDefault("PIERIA_DAEMON_URL", DEFAULT_DAEMON_URL);
  }
}
