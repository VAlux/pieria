package dev.alvo.pieria.cli.modules.task;

import dev.alvo.pieria.api.response.TaskListResponse;
import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.cli.log.ProgressListener;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Talks to the daemon's task surface ({@code /v1/tasks}) for the {@code pieria task} sub-commands:
 * list running and recently-finished tasks, re-attach to a task's live progress, and request
 * cancellation. Mirrors {@link dev.alvo.pieria.cli.modules.daemon.ProfileApiClient}'s plain
 * {@code HttpClient} + Jackson approach and surfaces failures as typed exceptions the commands map
 * to exit codes.
 */
public final class HttpTaskClient {

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private final String baseUrl;
  private final HttpClient http;
  private final ObjectMapper mapper;

  public HttpTaskClient(String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    // Tolerate task bodies that omit numeric progress fields (treat absent done/total as 0).
    this.mapper = JsonMapper.builder()
      .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
      .build();
  }

  /** All running and recently-finished tasks, newest first. */
  public TaskListResponse list() {
    return parse(get("/v1/tasks"), TaskListResponse.class);
  }

  /**
   * Request cooperative cancellation of {@code taskId}, returning its (now terminal or terminating)
   * snapshot. A 404 from the daemon surfaces as {@link NotFoundException}.
   */
  public TaskStatusResponse kill(String taskId) {
    String body = send(HttpRequest.newBuilder(uri("/v1/tasks/" + encode(taskId)))
      .timeout(Duration.ofSeconds(10))
      .DELETE()
      .build());
    return parse(body, TaskStatusResponse.class);
  }

  /**
   * Poll {@code /v1/tasks/{id}} until terminal, forwarding each RUNNING update to {@code progress},
   * and return the terminal snapshot. Re-attaching is identical to the original monitoring: the
   * same poll loop and progress rendering, just pointed at an already-running task.
   */
  public TaskStatusResponse attach(String taskId, ProgressListener progress) {
    URI uri = uri("/v1/tasks/" + encode(taskId));
    while (true) {
      TaskStatusResponse task = parse(send(HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build()), TaskStatusResponse.class);

      switch (task.status()) {
        case "RUNNING" -> {
          if (task.phase() != null) {
            progress.onProgress(task.phase(), task.done(), task.total());
          } else {
            progress.onProgress("starting", 0, 0);
          }
        }
        default -> {
          return task; // SUCCEEDED / FAILED / CANCELLED
        }
      }

      try {
        Thread.sleep(POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ApiException(-1, "interrupted while watching the task");
      }
    }
  }

  private String get(String path) {
    return send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).GET().build());
  }

  private String send(HttpRequest request) {
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new DaemonDownException("could not reach daemon at " + baseUrl, e);
    }
    int code = response.statusCode();
    if (code >= 200 && code < 300) {
      return response.body();
    }
    if (code == 404) {
      throw new NotFoundException(response.body());
    }
    throw new ApiException(code, response.body());
  }

  private <T> T parse(String body, Class<T> type) {
    try {
      return mapper.readValue(body, type);
    } catch (RuntimeException e) {
      throw new ApiException(0, "failed to parse daemon response: " + e.getMessage());
    }
  }

  private URI uri(String path) {
    return URI.create(baseUrl + path);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** Daemon could not be reached at all (connection refused / timeout). Exit code 3. */
  public static final class DaemonDownException extends RuntimeException {
    public DaemonDownException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** The daemon returned 404 (unknown task id). Exit code 4. */
  public static final class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
      super(message);
    }
  }

  /** Any other non-2xx response. Carries the HTTP status and body. Exit code 1. */
  public static final class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String body) {
      super("daemon returned HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body));
      this.status = status;
    }

    public int status() {
      return status;
    }
  }
}
