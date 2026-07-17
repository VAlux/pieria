package dev.alvo.pieria.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.request.OnboardPlanRequest;
import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.api.response.MemoryListResponse;
import dev.alvo.pieria.api.response.ProfileStatsResponse;
import dev.alvo.pieria.api.response.RecallResponse;
import dev.alvo.pieria.api.response.TaskSubmitResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thin HTTP client for driving a running Pieria daemon through its public REST surface — the same
 * contract a harness or the console uses. It reuses the {@code shared} request/response records so
 * the benchmark exercises the real wire contract, not an internal seam.
 *
 * <p><strong>Ingestion is async.</strong> A conversation's extraction pipeline (extract → verify →
 * classify → graph) runs through the local model and can take many minutes per fixture, so the
 * client submits to {@code POST /ingest/async} and polls {@code GET /v1/tasks/{id}} to completion
 * rather than holding one blocking HTTP request open (which would time out mid-ingest). Retrieval,
 * by contrast, is a single bounded synthesis call and stays synchronous with a generous timeout.
 *
 * <p>Recall is always requested with {@code debug=true}: the debug block carries the fused candidates
 * in rank order with per-channel provenance, which is what the harness scores for retrieval hit-rate
 * / MRR. Answer synthesis still runs (mode=SYNTHESIZED) so the synthesized answer is recorded for the
 * deferred faithfulness-judging pass.
 */
public final class DaemonEvalClient {

  public record OnboardCompletion(long coreWallMs, JsonNode result) {
  }

  private static final Logger log = LoggerFactory.getLogger(DaemonEvalClient.class);

  /** Short requests: async submit and each status/stats poll return promptly. */
  private static final Duration POLL_REQUEST_TIMEOUT = Duration.ofSeconds(30);
  /** How long to poll an ingest task before giving up (a slow fixture can take many minutes). */
  private static final Duration DEFAULT_INGEST_TASK_TIMEOUT = Duration.ofMinutes(30);
  /** A single synthesized recall is bounded but can be slow on CPU; keep the ceiling generous. */
  private static final Duration DEFAULT_RECALL_TIMEOUT = Duration.ofMinutes(15);

  private final String baseUrl;
  private final HttpClient http;
  private final ObjectMapper mapper;
  private final Duration ingestTaskTimeout;
  private final Duration recallTimeout;

  public DaemonEvalClient(String baseUrl) {
    this(baseUrl, DEFAULT_INGEST_TASK_TIMEOUT, DEFAULT_RECALL_TIMEOUT);
  }

  public DaemonEvalClient(String baseUrl, Duration ingestTaskTimeout, Duration recallTimeout) {
    this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    this.ingestTaskTimeout = Objects.requireNonNull(ingestTaskTimeout, "ingestTaskTimeout");
    this.recallTimeout = Objects.requireNonNull(recallTimeout, "recallTimeout");
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.mapper = new ObjectMapper()
      .findAndRegisterModules() // picks up jsr310 (MemoryResponse.createdAt is an Instant)
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  /** {@code true} once {@code GET /pieria-health} answers 2xx; false on any error/timeout. */
  public boolean healthy() {
    try {
      HttpResponse<String> resp = http.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/pieria-health"))
          .timeout(Duration.ofSeconds(5)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
      return resp.statusCode() / 100 == 2;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Ingest a conversation and block until extraction has stored its memories. Submits to
   * {@code POST /ingest/async} and polls the returned task to a terminal state; returns the number of
   * memories stored (from the task result). Throws if the task fails, is cancelled, or does not
   * finish within the ingest-task timeout.
   */
  public int ingest(String profile, String sessionId, List<IngestRequest.MessageDto> messages) {
    IngestRequest request = new IngestRequest(sessionId, messages);
    TaskSubmitResponse submit = post(
      "/v1/profiles/" + encode(profile) + "/ingest/async?label=eval-ingest",
      request, TaskSubmitResponse.class, POLL_REQUEST_TIMEOUT);
    return awaitIngestTask(submit.taskId());
  }

  /** Replace the profile-scoped ingestion overrides used by one isolated benchmark run. */
  public void configureIngestion(String profile, Map<String, Object> ingestionOverrides) {
    put("/v1/profiles/" + encode(profile) + "/config",
      Map.of("ingestion", ingestionOverrides), POLL_REQUEST_TIMEOUT);
  }

  /** Run one text-corpus onboarding plan without starting the graph child task. */
  public OnboardCompletion onboardText(String profile, Path root) {
    OnboardPlanRequest request = new OnboardPlanRequest(
      List.of(new SourceSpec.Text(root.toAbsolutePath().toString(), null, true)), false);
    long started = System.nanoTime();
    TaskSubmitResponse submit = post(
      "/v1/profiles/" + encode(profile) + "/onboard/async?label=eval-onboard",
      request, TaskSubmitResponse.class, POLL_REQUEST_TIMEOUT);
    JsonNode task = awaitTask(submit.taskId(), ingestTaskTimeout, "onboard");
    return new OnboardCompletion((System.nanoTime() - started) / 1_000_000L, task.path("result"));
  }

  public ProfileStatsResponse stats(String profile) {
    return get("/v1/profiles/" + encode(profile) + "/stats",
      ProfileStatsResponse.class, POLL_REQUEST_TIMEOUT);
  }

  public MemoryListResponse memories(String profile) {
    return get("/v1/profiles/" + encode(profile) + "/memories?session=pieria-init",
      MemoryListResponse.class, POLL_REQUEST_TIMEOUT);
  }

  /** POST /v1/profiles/{profile}/recall — full synthesized recall with debug provenance. */
  public RecallResponse recall(String profile, String query, int limit) {
    RecallRequest request = new RecallRequest(query, limit, true, RecallMode.SYNTHESIZED);
    return post("/v1/profiles/" + encode(profile) + "/recall", request, RecallResponse.class, recallTimeout);
  }

  /**
   * Block until the profile's vectorization outbox has drained (backlog {@code 0} or unavailable) or
   * {@code timeout} elapses. The real daemon vectorizes off a background worker, so recalling
   * immediately after ingest would leave the vector channels cold and understate retrieval quality.
   * Returns the number of milliseconds waited.
   */
  public long awaitVectorized(String profile, Duration timeout) {
    long start = System.nanoTime();
    long deadline = start + timeout.toNanos();
    long pollMs = 250;
    while (true) {
      Long backlog = vectorizationBacklog(profile);
      if (backlog == null || backlog == 0L) {
        return (System.nanoTime() - start) / 1_000_000L;
      }
      if (System.nanoTime() >= deadline) {
        log.warn("vectorization for profile {} still has backlog {} after {} — recalling anyway",
          profile, backlog, timeout);
        return (System.nanoTime() - start) / 1_000_000L;
      }
      sleep(pollMs);
      pollMs = Math.min(pollMs * 2, 2000);
    }
  }

  /**
   * Poll {@code GET /v1/tasks/{id}} until terminal. Returns the {@code result.count} on success;
   * throws on FAILED/CANCELLED or timeout. Phase transitions are logged so a long ingest shows
   * progress. The status DTO carries a Jackson-3 {@code JsonNode} field, so we parse into a generic
   * tree rather than the shared record.
   */
  private int awaitIngestTask(String taskId) {
    return awaitTask(taskId, ingestTaskTimeout, "ingest").path("result").path("count").asInt(0);
  }

  private JsonNode awaitTask(String taskId, Duration timeout, String operation) {
    long deadline = System.nanoTime() + timeout.toNanos();
    long pollMs = 1000;
    String lastPhase = null;
    while (true) {
      JsonNode task = getJson("/v1/tasks/" + encode(taskId), POLL_REQUEST_TIMEOUT);
      if (task == null) {
        // Transient read failure; retry until the deadline rather than aborting the whole run.
        if (System.nanoTime() >= deadline) {
          throw new IllegalStateException(operation + " task " + taskId + " status unreadable before timeout");
        }
        sleep(pollMs);
        continue;
      }
      String status = task.path("status").asText("");
      String phase = task.path("phase").asText(null);
      if (phase != null && !phase.equals(lastPhase)) {
        log.info("ingest task {} — phase {} ({}/{})", taskId, phase, task.path("done").asInt(), task.path("total").asInt());
        lastPhase = phase;
      }
      switch (status) {
        case "SUCCEEDED" -> {
          return task;
        }
        case "FAILED", "CANCELLED" -> throw new IllegalStateException(
          operation + " task " + taskId + " " + status + ": "
            + task.path("errorKind").asText("") + " " + task.path("errorMessage").asText(""));
        default -> {
          if (System.nanoTime() >= deadline) {
            throw new IllegalStateException(operation + " task " + taskId + " did not finish within " + timeout);
          }
          sleep(pollMs);
          pollMs = Math.min(pollMs + 500, 5000);
        }
      }
    }
  }

  /** Pending outbox depth for the profile, or {@code null} when unavailable (e.g. stats 404). */
  private Long vectorizationBacklog(String profile) {
    JsonNode stats = getJson("/v1/profiles/" + encode(profile) + "/stats", POLL_REQUEST_TIMEOUT);
    if (stats == null) {
      return null;
    }
    JsonNode backlog = stats.get("vectorizationBacklog");
    return backlog == null || backlog.isNull() ? null : backlog.asLong();
  }

  /** GET a JSON body as a tree, or {@code null} on any non-2xx / transport error. */
  private JsonNode getJson(String path, Duration timeout) {
    try {
      HttpResponse<String> resp = http.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout).GET().build(),
        HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        return null;
      }
      return mapper.readTree(resp.body());
    } catch (Exception e) {
      return null;
    }
  }

  private <T> T post(String path, Object body, Class<T> responseType, Duration timeout) {
    try {
      String json = mapper.writeValueAsString(body);
      HttpResponse<String> resp = http.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
          .timeout(timeout)
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
          .build(),
        HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        throw new IllegalStateException("POST " + path + " -> " + resp.statusCode() + ": "
          + truncate(resp.body()));
      }
      return mapper.readValue(resp.body(), responseType);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("POST " + path + " failed: " + e.getMessage(), e);
    }
  }

  private void put(String path, Object body, Duration timeout) {
    exchangeWithBody("PUT", path, body, timeout);
  }

  private <T> T get(String path, Class<T> responseType, Duration timeout) {
    try {
      HttpResponse<String> resp = http.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout).GET().build(),
        HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        throw new IllegalStateException("GET " + path + " -> " + resp.statusCode() + ": " + truncate(resp.body()));
      }
      return mapper.readValue(resp.body(), responseType);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("GET " + path + " failed: " + e.getMessage(), e);
    }
  }

  private void exchangeWithBody(String method, String path, Object body, Duration timeout) {
    try {
      String json = mapper.writeValueAsString(body);
      HttpResponse<String> resp = http.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
          .timeout(timeout)
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
          .build(),
        HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        throw new IllegalStateException(method + " " + path + " -> " + resp.statusCode() + ": "
          + truncate(resp.body()));
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(method + " " + path + " failed: " + e.getMessage(), e);
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting on the daemon", e);
    }
  }

  private static String encode(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8);
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 500 ? s : s.substring(0, 500) + "…";
  }
}
