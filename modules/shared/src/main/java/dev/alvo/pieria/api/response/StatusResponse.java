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
                             Setup setup) {

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
