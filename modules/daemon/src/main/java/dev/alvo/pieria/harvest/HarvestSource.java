package dev.alvo.pieria.harvest;

import java.time.Instant;

/**
 * A configured harvester bound to one profile.
 *
 * @param maxItemsPerRun    may be null for no limit
 * @param maxCharsPerRun    may be null for no limit
 * @param cursorJson        the last checkpoint's cursor, or null before the first one
 * @param sweepBlockedCount how many deletions the mass-delete guard is holding back, or null
 */
public record HarvestSource(String id, String profileId, String harvesterId, String configJson, SourceMode mode,
                            long intervalSeconds, Integer maxItemsPerRun, Long maxCharsPerRun, SourceStatus status,
                            String cursorJson, Instant nextRunAt, int consecutiveFailures, Long sweepBlockedCount,
                            Instant createdAt) {
}
