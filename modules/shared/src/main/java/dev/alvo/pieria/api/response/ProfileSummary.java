package dev.alvo.pieria.api.response;

import java.time.Instant;

/**
 * One row of GET /v1/profiles: a profile's name, when it was created, how many active
 * (non-superseded) memories it currently holds, how many sessions those memories came from, and
 * when the newest one was stored ({@code null} for an empty profile).
 */
public record ProfileSummary(String name, Instant createdAt, long memoryCount, long sessionCount, Instant lastMemoryAt) {

  public ProfileSummary(String name, Instant createdAt, long memoryCount) {
    this(name, createdAt, memoryCount, 0, null);
  }
}
