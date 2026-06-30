package dev.alvo.pieria.cli.modules.init;

import dev.alvo.pieria.api.request.CodeIndexRequest;
import dev.alvo.pieria.api.response.CodeIndexResponse;
import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.api.response.TaskSubmitResponse;
import dev.alvo.pieria.cli.log.ProgressListener;
import tools.jackson.databind.DeserializationFeature;
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

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private final String baseUrl;
  private final String label;
  private final HttpClient http;
  private final ObjectMapper mapper;

  public HttpCodeIndexClient(String baseUrl) {
    this(baseUrl, null);
  }

  /**
   * @param label optional task label sent as {@code ?label=}, so the task surfaces in
   *              {@code pieria task list} under a higher-level name (e.g. {@code "onboard"}) instead
   *              of the default {@code "code"}.
   */
  public HttpCodeIndexClient(String baseUrl, String label) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.label = label;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    // Tolerate task bodies that omit numeric progress fields (treat absent done/total as 0).
    this.mapper = JsonMapper.builder()
      .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
      .build();
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
  public CodeIndexResult index(String profile, CodeIndexRequest body, ProgressListener progress) {
    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (RuntimeException e) {
      return new Failure(-1, "failed to serialize request: " + e.getMessage());
    }

    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/profiles/" + profile + "/code/async" + labelQuery()))
      .timeout(Duration.ofSeconds(10))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .build();

    String taskId;
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status != 200 && status != 202) {
        return new Failure(status, response.body());
      }
      taskId = mapper.readValue(response.body(), TaskSubmitResponse.class).taskId();
    } catch (ConnectException | HttpConnectTimeoutException e) {
      return new DaemonDown(e.getMessage());
    } catch (Exception e) {
      return new Failure(-1, e.getMessage());
    }

    return poll(taskId, progress);
  }

  /** {@code "?label=onboard"} when a label is set, otherwise empty. */
  private String labelQuery() {
    if (label == null || label.isBlank()) {
      return "";
    }
    return "?label=" + java.net.URLEncoder.encode(label, StandardCharsets.UTF_8);
  }

  /**
   * Poll {@code /v1/tasks/{id}} until terminal, forwarding progress and mapping the outcome.
   */
  private CodeIndexResult poll(String taskId, ProgressListener progress) {
    URI uri = URI.create(baseUrl + "/v1/tasks/" + taskId);
    while (true) {
      TaskStatusResponse task;
      try {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
          return new Failure(response.statusCode(), response.body());
        }
        task = mapper.readValue(response.body(), TaskStatusResponse.class);
      } catch (ConnectException | HttpConnectTimeoutException e) {
        return new DaemonDown(e.getMessage());
      } catch (Exception e) {
        return new Failure(-1, e.getMessage());
      }

      switch (task.status()) {
        case "SUCCEEDED" -> {
          return new Success(mapper.treeToValue(task.result(), CodeIndexResponse.class));
        }
        case "FAILED" -> {
          return new Failure(-1, task.errorMessage() == null ? "code index task failed" : task.errorMessage());
        }
        default -> {
          // RUNNING but no phase yet: show a live "starting" tick so the reporter isn't silent
          // during the initial parse/setup window.
          if (task.phase() != null) {
            progress.onProgress(task.phase(), task.done(), task.total());
          } else {
            progress.onProgress("starting", 0, 0);
          }
        }
      }

      try {
        Thread.sleep(POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new Failure(-1, "interrupted while waiting for the code index task");
      }
    }
  }
}
