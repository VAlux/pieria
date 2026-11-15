package dev.alvo.pieria.domain.profile;

import java.time.Instant;

/**
 * A profile paired with its active (non-superseded) memory count, the number of distinct sessions
 * those memories came from, when the newest of them was stored ({@code null} when empty), and the
 * name of the profile it sits under ({@code null} at top level). The counts cover the profile's own
 * memories only, not those of its children. Produced by the storage layer for the profile-listing
 * endpoint.
 */
public record ProfileCount(Profile profile, long activeCount, long sessionCount, Instant lastMemoryAt,
                           String parentName) {

  public ProfileCount(Profile profile, long activeCount, long sessionCount, Instant lastMemoryAt) {
    this(profile, activeCount, sessionCount, lastMemoryAt, null);
  }
}
