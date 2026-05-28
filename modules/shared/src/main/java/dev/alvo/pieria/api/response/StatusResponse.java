package dev.alvo.pieria.api.response;

/**
 * Local operational status for the daemon. Intended for localhost use; no provider URLs, tokens, or
 * secrets are included.
 */
public record StatusResponse(String status,
                             String databasePath,
                             String backend,
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
