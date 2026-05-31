package dev.alvo.pieria.domain.profile;

import java.time.Instant;
import java.util.Map;

/**
 * Per-profile aggregate counts over the {@code memories} table. The profile's name/createdAt and the
 * vectorization backlog are supplied separately by the caller; this record holds only what a single
 * grouped scan of {@code memories} yields.
 *
 * @param totalActive   active (non-superseded) memory count
 * @param byType        active count keyed by wire type ("fact", "event", "instruction", "task")
 * @param superseded    superseded memory count
 * @param sessions      distinct sessions among active memories
 * @param firstMemoryAt earliest active memory's {@code created_at}, or {@code null} if none
 * @param lastMemoryAt  latest active memory's {@code created_at}, or {@code null} if none
 */
public record ProfileStats(long totalActive,
                           Map<String, Long> byType,
                           long superseded,
                           long sessions,
                           Instant firstMemoryAt,
                           Instant lastMemoryAt) {
}
