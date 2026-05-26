package dev.alvo.pieria.api.response;

import java.time.Instant;
import java.util.Map;

/**
 * Result of GET /v1/profiles/{name}/stats: a per-profile snapshot of what the store holds.
 *
 * @param name                 the profile name
 * @param createdAt            when the profile was first created
 * @param totalActive          count of active (non-superseded) memories
 * @param byType               active count keyed by wire type ("fact", "event", "instruction", "task")
 * @param superseded           count of superseded (logically deleted / replaced) memories
 * @param sessions             number of distinct sessions that contributed active memories
 * @param firstMemoryAt        creation time of the earliest active memory, or {@code null} if none
 * @param lastMemoryAt         creation time of the most recent active memory, or {@code null} if none
 * @param vectorizationBacklog pending vectorization-outbox depth, or {@code null} if unavailable
 */
public record ProfileStatsResponse(String name,
                                   Instant createdAt,
                                   long totalActive,
                                   Map<String, Long> byType,
                                   long superseded,
                                   long sessions,
                                   Instant firstMemoryAt,
                                   Instant lastMemoryAt,
                                   Long vectorizationBacklog) {
}
