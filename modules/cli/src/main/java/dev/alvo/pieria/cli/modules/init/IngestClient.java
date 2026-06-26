package dev.alvo.pieria.cli.modules.init;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.cli.log.ProgressListener;

/**
 * Seam between {@code pieria onboard} and the daemon's ingest endpoint.
 */
public interface IngestClient {

  /**
   * Cheap pre-flight check ({@code GET /pieria-health}) to distinguish "daemon down" from a real error.
   */
  Reachability ping();

  /**
   * Submit the transcript to {@code /v1/profiles/{profile}/ingest/async}, then poll the task until it
   * finishes, forwarding per-phase progress to {@code progress}.
   */
  IngestResult ingest(String profile, IngestRequest body, ProgressListener progress);

  /**
   * Result of a reachability check against the daemon.
   */
  enum Reachability {OK, DAEMON_DOWN}

  /**
   * Discriminated ingest outcome so the command can map cleanly to exit codes and guidance.
   */
  sealed interface IngestResult permits Success, ModelUnavailable, DaemonDown, Failure {
  }

  /**
   * 200 OK — {@code count} memories were stored (vectorization continues asynchronously).
   */
  record Success(int count) implements IngestResult {
  }

  /**
   * 503 / failed task — the daemon is up but the model call failed. {@code reason} is the daemon's
   * sanitized classification (e.g. "HTTP 404: model or deployment not found …"), or blank if none.
   */
  record ModelUnavailable(String reason) implements IngestResult {
  }

  /**
   * The daemon could not be reached (connection refused / timeout).
   */
  record DaemonDown(String detail) implements IngestResult {
  }

  /**
   * Any other non-success HTTP response.
   */
  record Failure(int status, String body) implements IngestResult {
  }
}
