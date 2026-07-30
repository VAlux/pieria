package dev.alvo.pieria.cli.modules.daemon;

import java.util.function.Function;

/**
 * Resolves the daemon base URL the same way across all CLI commands:
 * explicit option → {@code $PIERIA_DAEMON_URL} → localhost default.
 */
public final class DaemonUrls {

  public static final String DEFAULT_DAEMON_URL = "http://127.0.0.1:8077";

  private DaemonUrls() {
  }

  public static String resolve(String override) {
    return resolve(override, System::getenv);
  }

  /** Same precedence against an explicit env lookup, so callers holding one can stay consistent. */
  public static String resolve(String override, Function<String, String> env) {
    if (override != null && !override.isBlank()) {
      return override;
    }
    String fromEnv = env.apply("PIERIA_DAEMON_URL");
    return fromEnv == null || fromEnv.isBlank() ? DEFAULT_DAEMON_URL : fromEnv;
  }
}
