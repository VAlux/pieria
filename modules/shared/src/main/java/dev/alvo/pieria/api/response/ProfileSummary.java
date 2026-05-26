package dev.alvo.pieria.api.response;

import java.time.Instant;

/**
 * One row of GET /v1/profiles: a profile's name, when it was created, and how many active
 * (non-superseded) memories it currently holds.
 */
public record ProfileSummary(String name, Instant createdAt, long memoryCount) {
}
