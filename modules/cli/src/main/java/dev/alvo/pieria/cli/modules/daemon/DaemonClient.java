package dev.alvo.pieria.cli.modules.daemon;

import dev.alvo.pieria.api.response.HealthResponse;
import dev.alvo.pieria.api.response.StatusResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Talks to the local daemon's introspection endpoints ({@code GET /healthz}, {@code GET /statusz})
 * with short timeouts so the {@code pieria daemon} commands stay snappy.
 */
public final class DaemonClient {

  private final String baseUrl;
  private final HttpClient http;
  private final ObjectMapper mapper;

  public DaemonClient(String baseUrl) {
    // Strip a trailing slash so path concatenation is predictable.
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(3))
      .build();
    this.mapper = JsonMapper.builder().build();
  }

  /**
   * Cheap pre-flight check ({@code GET /healthz}) used to tell "running" from "down" without
   * parsing a body. Any HTTP response counts as reachable; only transport failures are down.
   */
  public Reachability ping() {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/healthz"))
      .timeout(Duration.ofSeconds(3))
      .GET()
      .build();
    try {
      http.send(request, HttpResponse.BodyHandlers.discarding());
      return Reachability.OK;
    } catch (Exception e) {
      return Reachability.DAEMON_DOWN;
    }
  }

  /**
   * Fetch the combined health + status snapshot.
   */
  public StatusResult status() {
    HttpResponse<String> healthResponse;
    try {
      healthResponse = get("/healthz");
    } catch (Exception e) {
      return new Down(e.getMessage());
    }

    HealthResponse health = parse(healthResponse.body(), HealthResponse.class);
    // /statusz is best-effort: a healthy daemon should serve it, but never let its failure mask
    // the reachability we already established via /healthz.
    StatusResponse status = fetchStatus();

    return healthResponse.statusCode() == 200
      ? new Reachable(health, status)
      : new Degraded(health, status);
  }

  private StatusResponse fetchStatus() {
    try {
      return parse(get("/statusz").body(), StatusResponse.class);
    } catch (Exception e) {
      return null;
    }
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
      .timeout(Duration.ofSeconds(3))
      .GET()
      .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private <T> T parse(String body, Class<T> type) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return mapper.readValue(body, type);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Result of a cheap reachability probe against the daemon.
   */
  public enum Reachability {OK, DAEMON_DOWN}

  /**
   * Discriminated status outcome so the command can map cleanly to messages and exit codes.
   */
  public sealed interface StatusResult permits Reachable, Degraded, Down {
  }

  /**
   * Daemon is up ({@code /healthz} returned 200). {@code status} may be {@code null} if the
   * {@code /statusz} call itself failed despite a healthy daemon.
   */
  public record Reachable(HealthResponse health, StatusResponse status) implements StatusResult {
  }

  /**
   * Daemon is reachable but unhealthy ({@code /healthz} returned 503, typically a DB-down probe).
   */
  public record Degraded(HealthResponse health, StatusResponse status) implements StatusResult {
  }

  /**
   * Daemon could not be reached (connection refused / timeout).
   */
  public record Down(String detail) implements StatusResult {
  }
}
