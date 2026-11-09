package dev.alvo.pieria.api.response;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * A harvest source. Secrets are listed by name only.
 *
 * @param sweepBlockedCount deletions the mass-delete guard is holding back, or null
 * @param acceptNextSweep   whether the next complete sweep may delete them
 * @param lastRun           the most recent run, or null
 */
public record SourceResponse(String id, String profile, String harvester, JsonNode config, List<String> secretNames, String mode,
                             long intervalSeconds, Integer maxItemsPerRun, Long maxCharsPerRun, String status, Instant nextRunAt,
                             int consecutiveFailures, Long sweepBlockedCount, boolean acceptNextSweep, boolean running,
                             List<String> warnings, SourceRunResponse lastRun) {
}
