package dev.alvo.pieria.mcp;

/**
 * Raised when the shim cannot reach the daemon over localhost (connection refused / timeout).
 * Carries only the base URL — no stack trace, no secrets — so the tool layer can surface a
 * concise message to the model (SPEC 10.1, phase-4 step 3).
 */
public class DaemonUnavailableException extends RuntimeException {

  public DaemonUnavailableException(String baseUrl) {
    super("Pieria daemon is not running at " + baseUrl);
  }
}
