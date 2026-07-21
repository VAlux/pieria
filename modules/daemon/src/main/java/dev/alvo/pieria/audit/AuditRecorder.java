package dev.alvo.pieria.audit;

import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.tools.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Builds and best-effort persists immutable audit events. */
@Service
public class AuditRecorder {
  private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

  private final AuditStore store;
  private final MemoryStore memories;
  private final ObjectMapper json;
  private final int maxBodyBytes;
  private final String serverVersion;

  public AuditRecorder(AuditStore store, MemoryStore memories, ObjectMapper json,
                       dev.alvo.pieria.config.AuditProperties properties) {
    this.store = store;
    this.memories = memories;
    this.json = json;
    this.maxBodyBytes = properties.maxBodyBytes();
    String version = AuditRecorder.class.getPackage().getImplementationVersion();
    this.serverVersion = version == null || version.isBlank() ? "unknown" : version;
  }

  public void recordHttp(String profileName, String operation, String requestId, AuditCaller caller,
                         String method, String path, String query, String requestMediaType,
                         String responseMediaType, Instant startedAt, Instant completedAt, int status,
                         CapturedPayload request, CapturedPayload response, String resourceId,
                         Throwable failure) {
    try {
      JsonNode requestJson = parse(request.body());
      JsonNode responseJson = parse(response.body());
      String sessionId = first(text(requestJson, "sessionId"), queryValue(query, "sessionId"),
        queryValue(query, "session"));
      String taskId = first(text(responseJson, "taskId"), text(requestJson, "taskId"));
      String resolvedResource = first(resourceId,
        operation.equals("memory.remember") ? text(responseJson, "id") : null);
      String errorKind = failure == null && status >= 400 ? text(responseJson, "error")
        : failure == null ? null : failure.getClass().getSimpleName();
      String errorMessage = failure == null && status >= 400 ? text(responseJson, "message")
        : failure == null ? null : message(failure);
      String profileId = memories.findProfile(profileName).map(Profile::id).orElse(null);
      store.append(new AuditEvent(
        UUID.randomUUID().toString(), profileId, profileName, "http", operation, requestId, null,
        taskId, sessionId, resolvedResource, caller.client(), caller.harness(), caller.channel(),
        caller.version(), serverVersion, caller.remoteAddress(), method, path, query,
        requestMediaType, responseMediaType, startedAt, completedAt,
        Math.max(0, Duration.between(startedAt, completedAt).toMillis()), status,
        status < 400 && failure == null ? "success" : "failure", errorKind, errorMessage, "{}",
        request.body(), request.bytes(), request.sha256(), request.truncated(), response.body(),
        response.bytes(), response.sha256(), response.truncated()));
    } catch (Throwable auditFailure) {
      log.error("failed to persist profile audit event requestId={} operation={} profile={}",
        requestId, operation, profileName, auditFailure);
    }
  }

  public void recordTaskTerminal(AuditRequestContext parent, String taskId, String taskKind,
                                 Instant startedAt, Instant completedAt, String status,
                                 JsonNode result, String errorKind, String errorMessage) {
    if (parent == null) {
      return;
    }
    try {
      var terminal = json.createObjectNode();
      terminal.put("status", status);
      terminal.put("taskKind", taskKind);
      if (result != null) terminal.set("result", result);
      if (errorKind != null) terminal.put("errorKind", errorKind);
      if (errorMessage != null) terminal.put("errorMessage", errorMessage);
      String body = json.writeValueAsString(terminal);
      CapturedPayload response = capture(body);
      Optional<Profile> profile = memories.findProfile(parent.profileName());
      // A hard profile delete must remain a complete wipe even when an older task finishes later.
      if (profile.isEmpty()) {
        return;
      }
      String profileId = profile.get().id();
      String outcome = switch (status) {
        case "SUCCEEDED" -> "success";
        case "CANCELLED" -> "cancelled";
        default -> "failure";
      };
      store.append(new AuditEvent(
        UUID.randomUUID().toString(), profileId, parent.profileName(), "task_terminal",
        "task.completed", UUID.randomUUID().toString(), parent.requestId(), taskId, null, taskId,
        parent.caller().client(), parent.caller().harness(), parent.caller().channel(),
        parent.caller().version(), serverVersion, parent.caller().remoteAddress(), null, null, null,
        null, "application/json", startedAt, completedAt,
        Math.max(0, Duration.between(startedAt, completedAt).toMillis()), null, outcome,
        errorKind, errorMessage, json.writeValueAsString(java.util.Map.of(
          "taskKind", taskKind, "submissionOperation", parent.operation())),
        "", 0, Hash.sha256Hex(new byte[0]), false, response.body(), response.bytes(),
        response.sha256(), response.truncated()));
    } catch (Throwable auditFailure) {
      log.error("failed to persist terminal task audit event parentRequestId={} taskId={}",
        parent.requestId(), taskId, auditFailure);
    }
  }

  private CapturedPayload capture(String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    int retained = Math.min(bytes.length, maxBodyBytes);
    return new CapturedPayload(new String(bytes, 0, retained, StandardCharsets.UTF_8), bytes.length,
      Hash.sha256Hex(bytes), bytes.length > maxBodyBytes);
  }

  private JsonNode parse(String body) {
    if (body == null || body.isBlank()) return null;
    try {
      return json.readTree(body);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field)) return null;
    String value = node.get(field).asText();
    return value == null || value.isBlank() ? null : value;
  }

  private static String queryValue(String query, String name) {
    if (query == null || query.isBlank()) return null;
    for (String pair : query.split("&")) {
      int equals = pair.indexOf('=');
      if (equals > 0 && pair.substring(0, equals).equals(name)) {
        return java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static String first(String... values) {
    for (String value : values) if (value != null && !value.isBlank()) return value;
    return null;
  }

  private static String message(Throwable failure) {
    return Optional.ofNullable(failure.getMessage()).orElseGet(failure::toString);
  }
}
