package dev.alvo.pieria.cli.modules.init;

import dev.alvo.pieria.api.request.CodeIndexRequest;
import dev.alvo.pieria.api.response.CodeIndexResponse;
import dev.alvo.pieria.cli.log.ProgressListener;

/**
 * Seam between {@code pieria onboard --source-code} and the daemon's code-index endpoint. Reuses
 * {@link IngestClient.Reachability} for the pre-flight check.
 */
public interface CodeIndexClient {

  /** Cheap pre-flight check ({@code GET /pieria-health}). */
  IngestClient.Reachability ping();

  /**
   * Submit the batch to {@code /v1/profiles/{profile}/code/async}, then poll the task until it
   * finishes, forwarding per-phase progress to {@code progress}.
   */
  CodeIndexResult index(String profile, CodeIndexRequest body, ProgressListener progress);

  /** Discriminated outcome so the command maps cleanly to exit codes. */
  sealed interface CodeIndexResult permits Success, DaemonDown, Failure {
  }

  /** 200 OK — the per-run summary. */
  record Success(CodeIndexResponse response) implements CodeIndexResult {
  }

  /** The daemon could not be reached (connection refused / timeout). */
  record DaemonDown(String detail) implements CodeIndexResult {
  }

  /** Any other non-success HTTP response. */
  record Failure(int status, String body) implements CodeIndexResult {
  }
}
