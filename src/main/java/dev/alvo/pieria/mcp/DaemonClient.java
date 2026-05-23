package dev.alvo.pieria.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * Thin HTTP client from the MCP shim to the local daemon's REST surface (SPEC 10.1). The shim holds
 * no state; every tool call forwards to {@code /v1/profiles/{name}/...} on the daemon. The daemon's
 * JSON bodies are passed through verbatim as tool output, so the model sees the same shape the REST
 * API documents.
 *
 * <p>When the daemon is unreachable (connection refused / timeout) the client returns a concise,
 * secret-free error string rather than surfacing a stack trace ({@link DaemonUnavailableException}).
 */
public class DaemonClient {

  private static final Logger log = LoggerFactory.getLogger(DaemonClient.class);

  private final RestClient http;
  private final String baseUrl;

  public DaemonClient(String baseUrl) {
    this(baseUrl, RestClient.builder().baseUrl(baseUrl).build());
  }

  /**
   * Test seam: inject a pre-built {@link RestClient} (e.g. pointed at a fake daemon).
   */
  public DaemonClient(String baseUrl, RestClient http) {
    this.baseUrl = baseUrl;
    this.http = http;
  }

  String baseUrl() {
    return baseUrl;
  }

  /**
   * POST /v1/profiles/{name}/recall — body {query, limit?}. Returns the raw response JSON.
   */
  String recall(String profile, String body) {
    return post("/v1/profiles/" + profile + "/recall", body);
  }

  /**
   * POST /v1/profiles/{name}/memories — body {type, content, ...}. Returns the raw response JSON.
   */
  String remember(String profile, String body) {
    return post("/v1/profiles/" + profile + "/memories", body);
  }

  /**
   * GET /v1/profiles/{name}/memories?type=&session= — returns the raw response JSON.
   */
  String list(String profile, String type, String session) {
    StringBuilder path = new StringBuilder("/v1/profiles/").append(profile).append("/memories");
    String sep = "?";
    if (type != null && !type.isBlank()) {
      path.append(sep).append("type=").append(type);
      sep = "&";
    }
    if (session != null && !session.isBlank()) {
      path.append(sep).append("session=").append(session);
    }
    return get(path.toString());
  }

  /**
   * DELETE /v1/profiles/{name}/memories/{id} — returns a short confirmation string.
   */
  String forget(String profile, String id) {
    return exchange(() -> http.delete()
      .uri("/v1/profiles/" + profile + "/memories/" + id)
      .retrieve()
      .onStatus(HttpStatusCode::isError, (req, res) -> {
      })
      .toBodilessEntity()
      .getStatusCode().toString());
  }

  private String post(String path, String body) {
    return exchange(() -> http.post()
      .uri(path)
      .header("Content-Type", "application/json")
      .body(body == null ? "{}" : body)
      .retrieve()
      .onStatus(HttpStatusCode::isError, (req, res) -> {
      })
      .body(String.class));
  }

  private String get(String path) {
    return exchange(() -> http.get()
      .uri(path)
      .retrieve()
      .onStatus(HttpStatusCode::isError, (req, res) -> {
      })
      .body(String.class));
  }

  /**
   * Runs an HTTP call, translating any transport-level failure into a concise tool error.
   */
  private String exchange(java.util.concurrent.Callable<String> call) {
    try {
      String body = call.call();
      return body == null ? "" : body;
    } catch (org.springframework.web.client.ResourceAccessException e) {
      // Connection refused / read timeout / unknown host — daemon is not running or unreachable.
      log.debug("daemon unreachable at {}", baseUrl, e);
      throw new DaemonUnavailableException(baseUrl);
    } catch (Exception e) {
      log.debug("daemon call failed", e);
      throw new DaemonUnavailableException(baseUrl);
    }
  }
}
