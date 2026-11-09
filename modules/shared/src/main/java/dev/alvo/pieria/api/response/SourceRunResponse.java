package dev.alvo.pieria.api.response;

import java.time.Instant;

/** One harvest run. Text fields never contain secret values. */
public record SourceRunResponse(String id, boolean dryRun, String status, Instant startedAt, Instant finishedAt, long itemsSeen,
                                long itemsChanged, long itemsDeleted, long memoriesStored, long chars, String errorClass,
                                String errorMessage, String stderrTail) {
}
