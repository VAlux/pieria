package dev.alvo.pieria.cli.modules.init;

import dev.alvo.pieria.api.request.IngestRequest;

/**
 * Seam between {@code pieria onboard} and the daemon's ingest endpoint.
 */
public interface IngestClient {

  /**
   * Cheap pre-flight check ({@code GET /pieria-health}) to distinguish "daemon down" from a real error.
   */
  Reachability ping();

  /**
   * POST the transcript to {@code /v1/profiles/{profile}/ingest}.
   */
  IngestResult ingest(String profile, IngestRequest body);

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
   * 503 — the daemon is up but its model provider is unavailable.
   */
  record ModelUnavailable() implements IngestResult {
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
