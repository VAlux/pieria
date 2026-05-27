package dev.alvo.pieria.cli.modules.init;

import dev.alvo.pieria.api.request.IngestRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class HttpIngestClient implements IngestClient {

  private final String baseUrl;
  private final HttpClient http;
  private final ObjectMapper mapper;

  public HttpIngestClient(String baseUrl) {
    // Strip a trailing slash so path concatenation is predictable.
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(3))
      .build();
    this.mapper = JsonMapper.builder().build();
  }

  @Override
  public Reachability ping() {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/pieria-health"))
      .timeout(Duration.ofSeconds(3))
      .GET()
      .build();
    try {
      // Any HTTP response (even a 503) means the daemon is reachable; only transport failures count.
      http.send(request, HttpResponse.BodyHandlers.discarding());
      return Reachability.OK;
    } catch (Exception e) {
      return Reachability.DAEMON_DOWN;
    }
  }

  @Override
  public IngestResult ingest(String profile, IngestRequest body) {
    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (RuntimeException e) {
      return new Failure(-1, "failed to serialize request: " + e.getMessage());
    }

    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/profiles/" + profile + "/ingest"))
      .timeout(Duration.ofMinutes(10)) // extraction over many docs can be slow on local models
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .build();

    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status == 200) {
        return new Success(parseCount(response.body()));
      }
      if (status == 503) {
        return new ModelUnavailable();
      }
      return new Failure(status, response.body());
    } catch (ConnectException | HttpConnectTimeoutException e) {
      return new DaemonDown(e.getMessage());
    } catch (Exception e) {
      return new Failure(-1, e.getMessage());
    }
  }

  /**
   * Read {@code count} from the ingest response via the tree model; default to 0 if absent.
   */
  private int parseCount(String body) {
    try {
      JsonNode node = mapper.readTree(body).get("count");
      return node != null ? node.asInt(0) : 0;
    } catch (RuntimeException e) {
      return 0;
    }
  }
}
