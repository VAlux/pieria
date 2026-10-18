package dev.alvo.pieria.api.response;

import java.time.Instant;
import java.util.List;

/** GET /v1/profiles/{name}/sessions: sessions seen through memories or raw messages, newest first. */
public record SessionListResponse(List<Session> sessions) {

  /** {@code harness} is the client that last reported the session in the audit log, when known. */
  public record Session(String sessionId, Instant firstSeenAt, Instant lastSeenAt, long memoryCount,
                        long messageCount, String harness) {
  }
}
