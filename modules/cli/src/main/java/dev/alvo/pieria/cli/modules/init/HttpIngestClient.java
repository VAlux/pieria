package dev.alvo.pieria.cli.modules.init;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.api.response.TaskSubmitResponse;
import dev.alvo.pieria.cli.log.ProgressListener;
import tools.jackson.databind.DeserializationFeature;
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

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private final String baseUrl;
  private final HttpClient http;
  private final ObjectMapper mapper;

  public HttpIngestClient(String baseUrl) {
    // Strip a trailing slash so path concatenation is predictable.
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(3))
      .build();
    // Tolerate task bodies that omit numeric progress fields (treat absent done/total as 0).
    this.mapper = JsonMapper.builder()
      .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
      .build();
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
  public IngestResult ingest(String profile, IngestRequest body, ProgressListener progress) {
    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (RuntimeException e) {
      return new Failure(-1, "failed to serialize request: " + e.getMessage());
    }

    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/profiles/" + profile + "/ingest/async"))
      .timeout(Duration.ofSeconds(10))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .build();

    String taskId;
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status == 503) {
        return new ModelUnavailable(errorMessageOf(response.body()));
      }
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

  /** Poll {@code /v1/tasks/{id}} until terminal, forwarding progress and mapping the outcome. */
  private IngestResult poll(String taskId, ProgressListener progress) {
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
          JsonNode count = task.result() == null ? null : task.result().get("count");
          return new Success(count != null ? count.asInt(0) : 0);
        }
        case "FAILED" -> {
          if ("model-unavailable".equals(task.errorKind())) {
            return new ModelUnavailable(task.errorMessage() == null ? "" : task.errorMessage());
          }
          return new Failure(-1, task.errorMessage() == null ? "ingest task failed" : task.errorMessage());
        }
        default -> {
          // A freshly-submitted task is RUNNING with no phase yet; the first real tick ("extract")
          // only lands after the first chunk finishes, which is tens of seconds on a CPU provider.
          // Emit an indeterminate "starting" tick so the reporter shows a live elapsed line instead
          // of sitting silent and looking frozen.
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
        return new Failure(-1, "interrupted while waiting for the ingest task");
      }
    }
  }

  /** Extract the {@code message} field from a daemon {@code ErrorResponse} body, or "" if absent. */
  private String errorMessageOf(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    try {
      JsonNode message = mapper.readTree(body).get("message");
      return message == null ? "" : message.asString();
    } catch (RuntimeException e) {
      return "";
    }
  }
}
