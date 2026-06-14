package dev.alvo.pieria.cli.modules.config;

import dev.alvo.pieria.cli.modules.init.IngestClient;

/**
 * Seam between the config commands / onboard push and the daemon's
 * {@code /v1/profiles/{name}/config} endpoint. Reuses {@link IngestClient.Reachability} for the
 * pre-flight check, mirroring {@code CodeIndexClient}.
 */
public interface ConfigClient {

  /**
   * Cheap pre-flight check ({@code GET /pieria-health}).
   */
  IngestClient.Reachability ping();

  /**
   * PUT the merged overrides JSON; the response body is the resulting effective config.
   */
  ConfigResult put(String profile, String overridesJson);

  /**
   * GET the profile's effective config JSON.
   */
  ConfigResult get(String profile);

  /**
   * Discriminated outcome so commands map cleanly to exit codes.
   */
  sealed interface ConfigResult permits Success, DaemonDown, Failure {
  }

  /**
   * 2xx — the effective config JSON.
   */
  record Success(String body) implements ConfigResult {
  }

  /**
   * The daemon could not be reached (connection refused / timeout).
   */
  record DaemonDown(String detail) implements ConfigResult {
  }

  /**
   * Any other non-success HTTP response.
   */
  record Failure(int status, String body) implements ConfigResult {
  }
}
