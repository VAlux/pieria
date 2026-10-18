package dev.alvo.pieria.domain.memory;

import java.time.Instant;

/**
 * A session seen in a profile: through extracted memories, raw messages, or both.
 *
 * @param harness the client that last reported this session in the audit log; null when unknown
 */
public record SessionActivity(String sessionId, Instant firstSeenAt, Instant lastSeenAt, long memoryCount,
                              long messageCount, String harness) {

  public SessionActivity withHarness(String value) {
    return new SessionActivity(sessionId, firstSeenAt, lastSeenAt, memoryCount, messageCount, value);
  }
}
