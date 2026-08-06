package dev.alvo.pieria.client;

import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.AuditListRequest;
import dev.alvo.pieria.api.response.AuditEventDetail;
import dev.alvo.pieria.api.response.AuditListResponse;
import dev.alvo.pieria.api.request.RememberRequest;
import dev.alvo.pieria.api.response.IngestResponse;
import dev.alvo.pieria.api.response.MemoryListResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.ProfileListResponse;
import dev.alvo.pieria.api.response.ProfileStatsResponse;
import dev.alvo.pieria.api.response.ProfileSummary;
import dev.alvo.pieria.api.response.RecallResponse;
import dev.alvo.pieria.api.response.TaskSubmitResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

public final class ProfileClient {
  private final DaemonTransport transport;

  ProfileClient(DaemonTransport transport) {
    this.transport = transport;
  }

  public ProfileClient(String baseUrl) {
    this(new DaemonTransport(baseUrl));
  }

  public ProfileClient(String baseUrl, ClientIdentity identity) {
    this(new DaemonTransport(baseUrl, identity));
  }

  private String profile(String name) {
    return "/v1/profiles/" + DaemonTransport.segment(name);
  }

  public ProfileListResponse list() {
    return transport.parse(transport.get("/v1/profiles", Duration.ofSeconds(15)), ProfileListResponse.class);
  }

  public ProfileSummary create(String name) {
    return transport.parse(transport.putEmpty(profile(name), Duration.ofSeconds(10)), ProfileSummary.class);
  }

  public void delete(String name) {
    transport.delete(profile(name), Duration.ofSeconds(30));
  }

  public ProfileStatsResponse stats(String name) {
    return stats(name, Duration.ofSeconds(15));
  }

  /**
   * Stats with an explicit timeout, for callers that cannot afford the default. Session-start hooks
   * block the harness until they return, so they pass a much shorter budget than an interactive
   * command would.
   */
  public ProfileStatsResponse stats(String name, Duration timeout) {
    return transport.parse(transport.get(profile(name) + "/stats", timeout), ProfileStatsResponse.class);
  }

  public MemoryListResponse memories(String name, String type, String session) {
    String path = DaemonTransport.withQuery(profile(name) + "/memories", "type", type, "session", session);
    return transport.parse(transport.get(path, Duration.ofSeconds(15)), MemoryListResponse.class);
  }

  public RecallResponse recall(String name, RecallRequest request) {
    return transport.parse(
      transport.post(profile(name) + "/recall", request, Duration.ofSeconds(60)), RecallResponse.class);
  }

  public MemoryResponse remember(String name, RememberRequest request) {
    return transport.parse(
      transport.post(profile(name) + "/memories", request, Duration.ofSeconds(60)), MemoryResponse.class);
  }

  /**
   * Ingest a raw harness transcript as a final capture (see the {@code partial} overload).
   */
  public IngestResponse ingestTranscript(String name, String sessionId, String harness,
                                         byte[] ndjson, Duration timeout) {
    return ingestTranscript(name, sessionId, harness, ndjson, false, timeout);
  }

  /**
   * Ingest a raw harness transcript. The daemon parses the NDJSON server-side using the parser
   * registered for {@code harness}, so hook callers ship the exact file bytes. A null or blank
   * {@code sessionId} is omitted so the daemon generates one.
   *
   * <p>{@code partial} marks a routine mid-session capture (an end-of-turn hook), letting the daemon
   * defer the still-growing trailing chunk instead of re-extracting it every turn. Final captures —
   * session end, pre-compaction — must pass {@code false} so nothing is left unextracted. The
   * parameter is omitted from the query string when false, so an older daemon simply ignores it and
   * behaves as it always did.
   */
  public IngestResponse ingestTranscript(String name, String sessionId, String harness,
                                         byte[] ndjson, boolean partial, Duration timeout) {
    String path = DaemonTransport.withQuery(profile(name) + "/ingest/transcript",
      "sessionId", sessionId, "harness", harness, "partial", partial ? "true" : null);
    return transport.parse(
      transport.postRaw(path, ndjson, "application/x-ndjson", null, timeout), IngestResponse.class);
  }

  /**
   * Submit a raw harness transcript for background ingestion. The response only acknowledges that
   * the daemon accepted the task; callers that need its result can poll the returned task id.
   */
  public TaskSubmitResponse ingestTranscriptAsync(String name, String sessionId, String harness,
                                                  byte[] ndjson, boolean partial, Duration timeout) {
    String path = DaemonTransport.withQuery(profile(name) + "/ingest/transcript/async",
      "sessionId", sessionId, "harness", harness, "partial", partial ? "true" : null);
    return transport.parse(
      transport.postRaw(path, ndjson, "application/x-ndjson", null, timeout), TaskSubmitResponse.class);
  }

  /**
   * Recall as a ready-to-inject text block rather than JSON. The daemon always runs the
   * {@code EVIDENCE} tier for this representation and answers {@code 204} when nothing was
   * recalled, which maps to an empty result.
   */
  public Optional<String> recallText(String name, RecallRequest request, Duration timeout) {
    String body = transport.postRaw(profile(name) + "/recall",
      transport.toJson(request).getBytes(StandardCharsets.UTF_8),
      "application/json", "text/plain", timeout);
    return body == null || body.isBlank() ? Optional.empty() : Optional.of(body);
  }

  public void forget(String name, String id) {
    transport.delete(profile(name) + "/memories/" + DaemonTransport.segment(id), Duration.ofSeconds(10));
  }

  public String export(String name) {
    return transport.get(profile(name) + "/export", Duration.ofSeconds(30));
  }

  public AuditListResponse audit(String name, AuditListRequest request) {
    String path = DaemonTransport.withQuery(profile(name) + "/audit",
      "q", request.search(), "operation", request.operation(), "client", request.client(),
      "harness", request.harness(), "channel", request.channel(), "outcome", request.outcome(),
      "status", string(request.status()), "session", request.session(), "taskId", request.taskId(),
      "requestId", request.requestId(), "from", request.from(), "to", request.to(),
      "truncated", string(request.truncated()), "limit", string(request.limit()),
      "cursor", request.cursor());
    return transport.parse(transport.get(path, Duration.ofSeconds(30)), AuditListResponse.class);
  }

  public AuditEventDetail auditDetail(String name, String id) {
    return transport.parse(transport.get(profile(name) + "/audit/" + DaemonTransport.segment(id),
      Duration.ofSeconds(30)), AuditEventDetail.class);
  }

  public String toJson(Object value) {
    return transport.toJson(value);
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }
}
