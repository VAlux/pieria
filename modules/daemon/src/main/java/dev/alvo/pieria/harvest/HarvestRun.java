package dev.alvo.pieria.harvest;

import java.time.Instant;

/** One execution of a harvester. Text fields never contain secret values. */
public record HarvestRun(String id, String sourceId, boolean dryRun, RunStatus status, Instant startedAt, Instant finishedAt,
                         long itemsSeen, long itemsChanged, long itemsDeleted, long memoriesStored, long chars,
                         String errorClass, String errorMessage, String stderrTail) {
}
