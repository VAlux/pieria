package dev.alvo.pieria.harvest;

import dev.alvo.pieria.harvester.protocol.ErrorClass;

import java.util.List;

/**
 * What one run did.
 *
 * @param chars             text reported by the harvester
 * @param estimatedChunks   extraction work the changed items represent
 * @param sweepBlockedCount deletions the mass-delete guard held back, or null
 * @param sessions          sessions that gained memories in this run
 */
public record HarvestRunSummary(String runId, RunStatus status, long itemsSeen, long itemsChanged, long itemsDeleted,
                                long memoriesStored, long chars, long estimatedChunks, boolean sweepApplied,
                                Long sweepBlockedCount, ErrorClass errorClass, String errorMessage, Long retryAfterSeconds,
                                List<String> sessions) {
}
