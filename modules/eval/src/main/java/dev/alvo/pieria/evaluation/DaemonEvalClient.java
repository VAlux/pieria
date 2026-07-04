package dev.alvo.pieria.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.response.IngestResponse;
import dev.alvo.pieria.api.response.ProfileStatsResponse;
import dev.alvo.pieria.api.response.RecallResponse;
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
 * <p>Retrieval is always requested with {@code debug=true}: the debug block carries the fused
 * candidates in rank order with per-channel provenance, which is what the harness scores for
 * retrieval hit-rate / MRR. Answer synthesis still runs (fast=false) so the synthesized answer is
 * recorded for the deferred faithfulness-judging pass.
 */
public final class DaemonEvalClient {

  private static final Logger log = LoggerFactory.getLogger(DaemonEvalClient.class);

  private final String baseUrl;
  private final HttpClient http;
  private final ObjectMapper mapper;
  private final Duration requestTimeout;

  public DaemonEvalClient(String baseUrl) {
    this(baseUrl, Duration.ofMinutes(5));
  }

  public DaemonEvalClient(String baseUrl, Duration requestTimeout) {
    this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
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

  /** POST /v1/profiles/{profile}/ingest — extract + store memories from a conversation. */
  public IngestResponse ingest(String profile, String sessionId, List<IngestRequest.MessageDto> messages) {
    IngestRequest request = new IngestRequest(sessionId, messages);
    return post("/v1/profiles/" + encode(profile) + "/ingest", request, IngestResponse.class);
  }

  /** POST /v1/profiles/{profile}/recall — full synthesized recall with debug provenance. */
  public RecallResponse recall(String profile, String query, int limit) {
    RecallRequest request = new RecallRequest(query, limit, true, false);
    return post("/v1/profiles/" + encode(profile) + "/recall", request, RecallResponse.class);
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
      try {
        Thread.sleep(pollMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return (System.nanoTime() - start) / 1_000_000L;
      }
      pollMs = Math.min(pollMs * 2, 2000);
    }
  }

  /** Pending outbox depth for the profile, or {@code null} when unavailable (e.g. stats 404). */
  private Long vectorizationBacklog(String profile) {
    try {
      HttpResponse<String> resp = http.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/v1/profiles/" + encode(profile) + "/stats"))
          .timeout(requestTimeout).GET().build(),
        HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        return null;
      }
      ProfileStatsResponse stats = mapper.readValue(resp.body(), ProfileStatsResponse.class);
      return stats.vectorizationBacklog();
    } catch (Exception e) {
      return null;
    }
  }

  private <T> T post(String path, Object body, Class<T> responseType) {
    try {
      String json = mapper.writeValueAsString(body);
      HttpResponse<String> resp = http.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
          .timeout(requestTimeout)
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
