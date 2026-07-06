package dev.alvo.pieria.cli.modules.init;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.api.response.TaskSubmitResponse;
import dev.alvo.pieria.cli.log.ProgressListener;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP {@link OnboardClient}: POSTs a {@link SourceSpec} to {@code /onboard/async} and polls
 * {@code /v1/tasks/{id}} until the onboarding task is terminal, forwarding progress along the way.
 */
public final class HttpOnboardClient implements OnboardClient {

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private final String baseUrl;
  private final String label;
  private final HttpClient http;
  private final ObjectMapper mapper;

  public HttpOnboardClient(String baseUrl) {
    this(baseUrl, null);
  }

  /**
   * @param label optional task label sent as {@code ?label=}, so the task surfaces in
   *              {@code pieria task list} under a higher-level name (e.g. {@code "onboard"}).
   */
  public HttpOnboardClient(String baseUrl, String label) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.label = label;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
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
      http.send(request, HttpResponse.BodyHandlers.discarding());
      return Reachability.OK;
    } catch (Exception e) {
      return Reachability.DAEMON_DOWN;
    }
  }

  @Override
  public OnboardResult onboard(String profile, SourceSpec spec, ProgressListener progress) {
    String json;
    try {
      json = mapper.writeValueAsString(spec);
    } catch (RuntimeException e) {
      return new Failure(-1, "failed to serialize request: " + e.getMessage());
    }

    HttpRequest request = HttpRequest.newBuilder(
        URI.create(baseUrl + "/v1/profiles/" + profile + "/onboard/async" + labelQuery()))
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
  private OnboardResult poll(String taskId, ProgressListener progress) {
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
          return success(task.result());
        }
        case "FAILED" -> {
          if ("model-unavailable".equals(task.errorKind())) {
            return new ModelUnavailable(task.errorMessage() == null ? "" : task.errorMessage());
          }
          return new Failure(-1, task.errorMessage() == null ? "onboard task failed" : task.errorMessage());
        }
        default -> {
          // A freshly-submitted task is RUNNING with no phase yet; emit an indeterminate "starting"
          // tick so the reporter shows a live elapsed line instead of sitting silent.
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
        return new Failure(-1, "interrupted while waiting for the onboard task");
      }
    }
  }

  /** Map the terminal task result JSON (an {@code OnboardResult}) into a {@link Success}. */
  private Success success(JsonNode result) {
    if (result == null) {
      return new Success("", 0, 0, null, null, null);
    }
    return new Success(
      text(result, "sourceType"),
      intOr(result, "documents"),
      intOr(result, "memoriesStored"),
      nullableInt(result, "symbols"),
      nullableInt(result, "edges"),
      nullableInt(result, "summariesStored"));
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || v.isNull() ? "" : v.asString();
  }

  private static int intOr(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || v.isNull() ? 0 : v.asInt(0);
  }

  private static Integer nullableInt(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || v.isNull() ? null : v.asInt(0);
  }

  /** {@code "?label=onboard"} when a label is set, otherwise empty. */
  private String labelQuery() {
    if (label == null || label.isBlank()) {
      return "";
    }
    return "?label=" + URLEncoder.encode(label, StandardCharsets.UTF_8);
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
