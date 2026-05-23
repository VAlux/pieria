package dev.alvo.pieria.mcp;

import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.RememberRequest;
import dev.alvo.pieria.api.response.ErrorResponse;
import dev.alvo.pieria.api.response.MemoryListResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.RecallResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import tools.jackson.databind.ObjectMapper;

/**
 * Thin, type-safe HTTP client from the MCP shim to the local daemon's REST surface (SPEC 10.1). The
 * shim holds no state; every tool call forwards to {@code /v1/profiles/{name}/...} on the daemon.
 *
 * <p>Requests and responses are exchanged using the shared HTTP-contract DTOs
 * ({@link RecallRequest}/{@link RememberRequest} out; {@link MemoryResponse}/{@link RecallResponse}/
 * {@link MemoryListResponse} back). Request bodies are serialized with {@code NON_NULL} inclusion so
 * optional fields are omitted on the wire (matching the daemon's expectations). The deserialized
 * response is re-serialized as the tool's output string, so the model still sees the documented JSON
 * shape — now mediated by the typed contract instead of opaque pass-through.
 *
 * <p>When the daemon is unreachable (connection refused / timeout) the client throws a concise,
 * secret-free {@link DaemonUnavailableException}.
 */
public class DaemonClient {

  private static final Logger log = LoggerFactory.getLogger(DaemonClient.class);

  private final RestClient http;
  private final String baseUrl;
  private final ObjectMapper json;

  public DaemonClient(String baseUrl) {
    this(baseUrl, new ObjectMapper());
  }

  public DaemonClient(String baseUrl, ObjectMapper json) {
    this(baseUrl, RestClient.builder().baseUrl(baseUrl).build(), json);
  }

  /**
   * Test seam: inject a pre-built {@link RestClient} (e.g. pointed at a fake daemon).
   */
  public DaemonClient(String baseUrl, RestClient http) {
    this(baseUrl, http, new ObjectMapper());
  }

  public DaemonClient(String baseUrl, RestClient http, ObjectMapper json) {
    this.baseUrl = baseUrl;
    this.http = http;
    this.json = json;
  }

  String baseUrl() {
    return baseUrl;
  }

  /**
   * POST /v1/profiles/{name}/recall. Returns the daemon's {@link RecallResponse} re-serialized to JSON.
   */
  String recall(String profile, RecallRequest request) {
    String body = post("/v1/profiles/" + profile + "/recall", write(request));
    return passthrough(body, RecallResponse.class);
  }

  /**
   * POST /v1/profiles/{name}/memories. Returns the daemon's {@link MemoryResponse} re-serialized.
   */
  String remember(String profile, RememberRequest request) {
    String body = post("/v1/profiles/" + profile + "/memories", write(request));
    return passthrough(body, MemoryResponse.class);
  }

  /**
   * GET /v1/profiles/{name}/memories?type=&session= — returns the {@link MemoryListResponse} re-serialized.
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
    String body = get(path.toString());
    return passthrough(body, MemoryListResponse.class);
  }

  /**
   * DELETE /v1/profiles/{name}/memories/{id} — returns a short confirmation string.
   */
  String forget(String profile, String id) {
    return exchange(() -> http.delete()
      .uri("/v1/profiles/" + profile + "/memories/" + id)
      .retrieve()
      .onStatus(HttpStatusCode::isError, (_, _) -> {
      })
      .toBodilessEntity()
      .getStatusCode().toString());
  }

  /**
   * Deserialize the daemon's body into the typed contract DTO and re-serialize it as the tool output
   * (the type-safety payoff). If the body is not a successful payload of {@code type} (e.g. an
   * {@link ErrorResponse} from a 4xx/5xx, or anything else), it is passed through verbatim so the
   * model still sees the daemon's message.
   */
  private <T> String passthrough(String body, Class<T> type) {
    if (body == null || body.isBlank()) {
      return "";
    }
    try {
      json.readValue(body, type);
      return body;
    } catch (Exception notTheExpectedShape) {
      // Likely an ErrorResponse or other body — surface the daemon's own JSON unchanged.
      try {
        json.readValue(body, ErrorResponse.class);
        return body;
      } catch (Exception e) {
        return body;
      }
    }
  }

  private String write(Object request) {
    if (request instanceof RecallRequest recall) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("query", recall.query());
      putIfPresent(body, "limit", recall.limit());
      putIfPresent(body, "debug", recall.debug());
      return json.writeValueAsString(body);
    }
    if (request instanceof RememberRequest remember) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("type", remember.type());
      body.put("content", remember.content());
      putIfPresent(body, "sessionId", remember.sessionId());
      putIfPresent(body, "topicKey", remember.topicKey());
      putIfPresent(body, "payload", remember.payload());
      return json.writeValueAsString(body);
    }
    return json.writeValueAsString(request);
  }

  private static void putIfPresent(Map<String, Object> body, String key, Object value) {
    if (value != null) {
      body.put(key, value);
    }
  }

  private String post(String path, String body) {
    return exchange(() -> http.post()
      .uri(path)
      .header("Content-Type", "application/json")
      .body(body == null ? "{}" : body)
      .retrieve()
      .onStatus(HttpStatusCode::isError, (_, _) -> {
      })
      .body(String.class));
  }

  private String get(String path) {
    return exchange(() -> http.get()
      .uri(path)
      .retrieve()
      .onStatus(HttpStatusCode::isError, (_, _) -> {
      })
      .body(String.class));
  }

  /**
   * Runs an HTTP call, translating any transport-level failure into a concise tool error.
   */
  private String exchange(Callable<String> call) {
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
