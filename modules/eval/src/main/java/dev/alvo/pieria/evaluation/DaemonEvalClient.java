package dev.alvo.pieria.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.response.IngestResponse;
import dev.alvo.pieria.api.response.MemoryListResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.ProfileStatsResponse.ProfileSpend;
import dev.alvo.pieria.api.response.RecallResponse;
import dev.alvo.pieria.evaluation.EvaluationReport.Spend;
import dev.alvo.pieria.api.response.TaskLaneProgress;
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
import java.util.List;
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
 * <p>Recall runs at {@code mode=SYNTHESIZED}: the harness scores the fused, rank-ordered
 * {@code memories} the daemon returns for retrieval hit-rate / MRR, and records the synthesized answer
 * for the deferred faithfulness-judging pass.
 */
public final class DaemonEvalClient {

  private static final Logger log = LoggerFactory.getLogger(DaemonEvalClient.class);

  /**
   * Short requests: async submit and each status/stats poll return promptly.
   */
  private static final Duration POLL_REQUEST_TIMEOUT = Duration.ofSeconds(30);
  /**
   * How long to poll an ingest task before giving up (a slow fixture can take many minutes).
   */
  private static final Duration DEFAULT_INGEST_TASK_TIMEOUT = Duration.ofMinutes(30);
  /**
   * A single synthesized recall is bounded but can be slow on CPU; keep the ceiling generous.
   */
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
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      // Send message timestamps as ISO-8601 strings rather than epoch decimals, so the wire body
      // stays the documented contract and is readable when a request is logged or replayed.
      .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
  }

  /**
   * {@code true} once {@code GET /pieria-health} answers 2xx; false on any error/timeout.
   */
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

  /**
   * POST /v1/profiles/{profile}/recall — full synthesized recall. The returned {@code memories} are
   * the RRF-fused candidates in rank order, which is exactly what the harness scores.
   */
  public RecallResponse recall(String profile, String query, int limit) {
    RecallRequest request = new RecallRequest(query, limit, false, RecallMode.SYNTHESIZED);
    return post("/v1/profiles/" + encode(profile) + "/recall", request, RecallResponse.class, recallTimeout);
  }

  /**
   * GET /v1/profiles/{profile}/memories — every memory the profile holds, in store order. This is
   * the corpus the extraction gate is judged against: it answers "did the fact survive ingestion at
   * all", independently of whether recall would have ranked it. Returns an empty list when the
   * listing is unavailable.
   */
  public List<String> memories(String profile) {
    JsonNode listing = getJson("/v1/profiles/" + encode(profile) + "/memories", recallTimeout);
    if (listing == null) {
      log.warn("memory listing for profile {} unavailable — extraction coverage will read as 0", profile);
      return List.of();
    }
    return mapper.convertValue(listing, MemoryListResponse.class).memories().stream()
      .map(MemoryResponse::content)
      .filter(content -> content != null && !content.isBlank())
      .toList();
  }

  /**
   * The profile's real inference spend so far, from {@code GET /stats}. The daemon costs each tier
   * server-side from the configured {@code pieria.stats.spend.<tier>} prices, so a run benchmarked
   * with {@code --config} reports actual money; without prices the token counts still come back and
   * the cost reads zero. Returns {@link Spend#NONE} when stats are unavailable.
   */
  public Spend spend(String profile) {
    JsonNode stats = getJson("/v1/profiles/" + encode(profile) + "/stats", POLL_REQUEST_TIMEOUT);
    if (stats == null || stats.path("spend").isMissingNode() || stats.path("spend").isNull()) {
      return Spend.NONE;
    }
    ProfileSpend reported = mapper.convertValue(stats.get("spend"), ProfileSpend.class);
    List<Spend.TierSpend> tiers = reported.tiers() == null ? List.of()
      : reported.tiers().stream()
        .map(t -> new Spend.TierSpend(t.tier(), t.calls(), t.promptTokens(), t.completionTokens(),
          t.costUsd()))
        .toList();
    return new Spend(tiers, reported.totalPromptTokens(), reported.totalCompletionTokens(),
      reported.totalCostUsd(), reported.costAvailable());
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
   * tree rather than the shared record — but the ingest-specific {@code result} sub-tree is always
   * shaped like {@link IngestResponse}, so it converts cleanly.
   */
  private int awaitIngestTask(String taskId) {
    JsonNode result = awaitTask(taskId, ingestTaskTimeout, "ingest").path("result");
    return result.isMissingNode() ? 0 : mapper.convertValue(result, IngestResponse.class).count();
  }

  private JsonNode awaitTask(String taskId, Duration timeout, String operation) {
    long deadline = System.nanoTime() + timeout.toNanos();
    long pollMs = 1000;
    String lastLaneProgress = null;
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
      JsonNode laneNode = task.path("lanes").isArray() && !task.path("lanes").isEmpty()
        ? task.path("lanes").get(0) : null;
      TaskLaneProgress lane = laneNode == null ? null : mapper.convertValue(laneNode, TaskLaneProgress.class);
      String laneProgress = lane == null ? null : lane.name() + ':' + lane.state() + ':' + lane.phase();
      if (laneProgress != null && !laneProgress.equals(lastLaneProgress)) {
        String phase = lane.phase() == null || lane.phase().isBlank() ? "starting" : lane.phase();
        log.info("{} task {} — {} {} ({}/{})", operation, taskId, lane.name(),
          phase, lane.done(), lane.total());
        lastLaneProgress = laneProgress;
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

  /**
   * Pending outbox depth for the profile, or {@code null} when unavailable (e.g. stats 404).
   */
  private Long vectorizationBacklog(String profile) {
    JsonNode stats = getJson("/v1/profiles/" + encode(profile) + "/stats", POLL_REQUEST_TIMEOUT);
    if (stats == null) {
      return null;
    }
    JsonNode backlog = stats.get("vectorizationBacklog");
    return backlog == null || backlog.isNull() ? null : backlog.asLong();
  }

  /**
   * GET a JSON body as a tree, or {@code null} on any non-2xx / transport error.
   */
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
