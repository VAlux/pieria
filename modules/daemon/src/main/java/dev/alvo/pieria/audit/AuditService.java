package dev.alvo.pieria.audit;

import dev.alvo.pieria.api.request.AuditListRequest;
import dev.alvo.pieria.api.response.AuditEventDetail;
import dev.alvo.pieria.api.response.AuditEventSummary;
import dev.alvo.pieria.api.response.AuditListResponse;
import dev.alvo.pieria.domain.error.NotFoundException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Validates audit queries and maps persistent events to stable API responses.
 */
@Service
public class AuditService {
  private final AuditStore store;

  public AuditService(AuditStore store) {
    this.store = store;
  }

  private static AuditEventSummary summary(AuditEvent e) {
    return new AuditEventSummary(e.id(), e.eventType(), e.operation(), e.requestId(),
      e.parentRequestId(), e.taskId(), e.sessionId(), e.resourceId(), e.client(), e.harness(),
      e.channel(), e.startedAt(), e.completedAt(), e.durationMs(), e.httpStatus(), e.outcome(),
      e.errorKind(), e.errorMessage(), e.requestBytes(), e.requestTruncated(), e.responseBytes(),
      e.responseTruncated(), preview(e.responseBody()));
  }

  private static AuditEventDetail detail(AuditEvent e) {
    return new AuditEventDetail(e.id(), e.eventType(), e.operation(), e.requestId(),
      e.parentRequestId(), e.taskId(), e.sessionId(), e.resourceId(), e.client(), e.harness(),
      e.channel(), e.clientVersion(), e.serverVersion(), e.remoteAddress(), e.method(), e.path(),
      e.queryString(), e.requestMediaType(), e.responseMediaType(), e.startedAt(), e.completedAt(),
      e.durationMs(), e.httpStatus(), e.outcome(), e.errorKind(), e.errorMessage(), e.metadata(),
      e.requestBody(), e.requestBytes(), e.requestSha256(), e.requestTruncated(), e.responseBody(),
      e.responseBytes(), e.responseSha256(), e.responseTruncated());
  }

  private static String preview(String body) {
    if (body == null || body.isBlank()) return "";
    String collapsed = body.strip().replaceAll("\\s+", " ");
    return collapsed.length() <= 240 ? collapsed : collapsed.substring(0, 239) + "…";
  }

  private static Instant instant(String value, String field) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(field + " must be an ISO-8601 instant");
    }
  }

  private static String encodeCursor(AuditEvent event) {
    String raw = event.completedAt() + "\n" + event.id();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static Cursor decodeCursor(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
      int newline = raw.indexOf('\n');
      if (newline <= 0 || newline == raw.length() - 1) throw new IllegalArgumentException();
      return new Cursor(Instant.parse(raw.substring(0, newline)), raw.substring(newline + 1));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("invalid audit cursor");
    }
  }

  public AuditListResponse search(String profileName, AuditListRequest request) {
    int limit = request.limit() == null ? 50 : request.limit();
    if (limit < 1 || limit > 200) {
      throw new IllegalArgumentException("limit must be between 1 and 200");
    }
    Cursor cursor = decodeCursor(request.cursor());
    Instant from = instant(request.from(), "from");
    Instant to = instant(request.to(), "to");
    if (from != null && to != null && from.isAfter(to)) {
      throw new IllegalArgumentException("from must not be after to");
    }
    AuditQuery query = new AuditQuery(request.search(), request.operation(), request.client(),
      request.harness(), request.channel(), request.outcome(), request.status(), request.session(),
      request.taskId(), request.requestId(), from, to,
      request.truncated(), cursor == null ? null : cursor.time(), cursor == null ? null : cursor.id(),
      limit + 1);
    List<AuditEvent> found = store.search(profileName, query);
    boolean more = found.size() > limit;
    List<AuditEvent> page = more ? new ArrayList<>(found.subList(0, limit)) : found;
    String next = more && !page.isEmpty() ? encodeCursor(page.getLast()) : null;
    return new AuditListResponse(page.stream().map(AuditService::summary).toList(), next);
  }

  public AuditEventDetail detail(String profileName, String id) {
    AuditEvent e = store.find(profileName, id)
      .orElseThrow(() -> new NotFoundException("No audit event with id '" + id + "'"));
    return detail(e);
  }

  private record Cursor(Instant time, String id) {
  }
}
