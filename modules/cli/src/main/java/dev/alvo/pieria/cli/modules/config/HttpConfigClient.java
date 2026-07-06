package dev.alvo.pieria.cli.modules.config;

import dev.alvo.pieria.cli.modules.init.Reachability;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class HttpConfigClient implements ConfigClient {

  private final String baseUrl;
  private final HttpClient http;

  public HttpConfigClient(String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @Override
  public Reachability ping() {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/pieria-health"))
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

  @Override
  public ConfigResult put(String profile, String overridesJson) {
    HttpRequest request = configRequest(profile)
      .header("Content-Type", "application/json")
      .PUT(HttpRequest.BodyPublishers.ofString(overridesJson, StandardCharsets.UTF_8))
      .build();
    return send(request);
  }

  @Override
  public ConfigResult get(String profile) {
    return send(configRequest(profile).GET().build());
  }

  private HttpRequest.Builder configRequest(String profile) {
    return HttpRequest.newBuilder(URI.create(baseUrl + "/v1/profiles/" + profile + "/config"))
      .timeout(Duration.ofSeconds(10));
  }

  private ConfigResult send(HttpRequest request) {
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        return new Success(response.body());
      }
      return new Failure(status, response.body());
    } catch (ConnectException | HttpConnectTimeoutException e) {
      return new DaemonDown(e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Failure(-1, e.getMessage());
    } catch (Exception e) {
      return new Failure(-1, e.getMessage());
    }
  }
}
