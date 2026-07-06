package dev.alvo.pieria.cli.modules.init;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.cli.log.ProgressListener;

/**
 * Seam between {@code pieria onboard} and the daemon's onboarding endpoint. The client sends one
 * {@link SourceSpec} (the daemon does discovery/reading/fetching itself), polls the resulting task,
 * and maps the outcome so the command can render progress and exit codes.
 */
public interface OnboardClient {

  /**
   * Cheap pre-flight check ({@code GET /pieria-health}) to distinguish "daemon down" from a real error.
   */
  Reachability ping();

  /**
   * Submit {@code spec} to {@code /v1/profiles/{profile}/onboard/async}, then poll the task until it
   * finishes, forwarding per-phase progress to {@code progress}.
   */
  OnboardResult onboard(String profile, SourceSpec spec, ProgressListener progress);

  /**
   * Discriminated onboard outcome so the command can map cleanly to exit codes and guidance.
   */
  sealed interface OnboardResult permits Success, ModelUnavailable, DaemonDown, Failure {
  }

  /**
   * Terminal success — the source was ingested. {@code symbols}/{@code edges}/{@code summariesStored}
   * are populated only for the source-code source (null for content sources).
   */
  record Success(String sourceType, int documents, int memoriesStored,
                 Integer symbols, Integer edges, Integer summariesStored) implements OnboardResult {
  }

  /**
   * The daemon is up but a model call failed. {@code reason} is the daemon's sanitized classification.
   */
  record ModelUnavailable(String reason) implements OnboardResult {
  }

  /**
   * The daemon could not be reached (connection refused / timeout).
   */
  record DaemonDown(String detail) implements OnboardResult {
  }

  /**
   * Any other non-success outcome.
   */
  record Failure(int status, String body) implements OnboardResult {
  }
}
