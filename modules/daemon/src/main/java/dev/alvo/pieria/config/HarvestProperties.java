package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Knowledge-harvester runtime settings.
 *
 * @param enabled               master switch for the scheduler
 * @param pollMs                how often the scheduler looks for due sources
 * @param maxConcurrentRuns     how many harvesters may run at once
 * @param extractionConcurrency parallel model calls per harvested item, kept low so interactive
 *                              ingest and recall aren't starved
 * @param runTimeout            the limit on one run, including extraction
 * @param sweepGuardShare       share of a source's items a sweep may delete before it is held back
 * @param sweepGuardFloor       deletions always allowed regardless of share, so a tiny site can lose a page
 * @param maxBackoff            the longest wait between retries of a failing source
 * @param root                  where harvesters, pins and secrets live; blank means under the app-data root
 */
@ConfigurationProperties(prefix = "pieria.harvest")
public record HarvestProperties(
  @DefaultValue("true") boolean enabled,
  @DefaultValue("30000") long pollMs,
  @DefaultValue("1") int maxConcurrentRuns,
  @DefaultValue("1") int extractionConcurrency,
  @DefaultValue("PT6H") Duration runTimeout,
  @DefaultValue("0.2") double sweepGuardShare,
  @DefaultValue("2") int sweepGuardFloor,
  @DefaultValue("PT24H") Duration maxBackoff,
  @DefaultValue("") String root) {
}
