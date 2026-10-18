package dev.alvo.pieria.domain.profile;

import java.time.Instant;

/**
 * A profile paired with its active (non-superseded) memory count, the number of distinct sessions
 * those memories came from, and when the newest of them was stored ({@code null} when empty).
 * Produced by the storage layer for the profile-listing endpoint.
 */
public record ProfileCount(Profile profile, long activeCount, long sessionCount, Instant lastMemoryAt) {
}
