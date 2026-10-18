package dev.alvo.pieria.api.response;

/**
 * Local operational status for the daemon. Intended for localhost use; no provider URLs, tokens, or
 * secrets are included.
 *
 * <p>{@code vectorSearch} reports whether the sqlite-vec extension actually loaded. Vector search
 * degrades silently to FTS-only when it does not, so this is the one signal that distinguishes a
 * fully working daemon from a quietly reduced one — the CI smoke test asserts on it.
 */
public record StatusResponse(String status,
                             String databasePath,
                             String backend,
                             boolean vectorSearch,
                             String modelProvider,
                             String extractionModel,
                             String synthesisModel,
                             String embeddingModel,
                             Long vectorizationOutboxDepth,
                             Setup setup,
                             String host,
                             int port,
                             String providerLabel,
                             String providerType,
                             int apiVersion,
                             String daemonVersion,
                             Restart restart) {

  /**
   * Whether the console may restart the daemon: only when a service manager relaunches it after
   * exit ({@code mechanism} is {@code launchd} or {@code systemd}); otherwise {@code pieria daemon
   * restart} from a terminal is the way.
   */
  public record Restart(boolean supported, String mechanism) {
  }


  public record Setup(boolean enabled,
                      boolean directoriesReady,
                      boolean databaseParentReady,
                      String modelStatus,
                      String modelPullPolicy,
                      String configDir,
                      String logsDir,
                      String runtimeDir) {
  }
}
