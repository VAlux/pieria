package dev.alvo.pieria.cli.modules.init;

import dev.alvo.pieria.api.request.CodeIndexRequest;
import dev.alvo.pieria.api.response.CodeIndexResponse;
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

public final class HttpCodeIndexClient implements CodeIndexClient {

  private final String baseUrl;
  private final HttpClient http;
  private final ObjectMapper mapper;

  public HttpCodeIndexClient(String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    this.mapper = JsonMapper.builder().build();
  }

  @Override
  public IngestClient.Reachability ping() {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/pieria-health"))
      .timeout(Duration.ofSeconds(3))
      .GET()
      .build();
    try {
      http.send(request, HttpResponse.BodyHandlers.discarding());
      return IngestClient.Reachability.OK;
    } catch (Exception e) {
      return IngestClient.Reachability.DAEMON_DOWN;
    }
  }

  @Override
  public CodeIndexResult index(String profile, CodeIndexRequest body) {
    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (RuntimeException e) {
      return new Failure(-1, "failed to serialize request: " + e.getMessage());
    }

    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/profiles/" + profile + "/code"))
      .timeout(Duration.ofMinutes(10)) // parsing a large repo can take a while
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .build();

    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status == 200) {
        return new Success(mapper.readValue(response.body(), CodeIndexResponse.class));
      }
      return new Failure(status, response.body());
    } catch (ConnectException | HttpConnectTimeoutException e) {
      return new DaemonDown(e.getMessage());
    } catch (Exception e) {
      return new Failure(-1, e.getMessage());
    }
  }
}
