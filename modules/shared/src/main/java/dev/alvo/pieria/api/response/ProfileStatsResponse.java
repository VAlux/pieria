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
 * @param impact               lifetime token-savings counters, or {@code null} if not tracked
 */
public record ProfileStatsResponse(String name,
                                   Instant createdAt,
                                   long totalActive,
                                   Map<String, Long> byType,
                                   long superseded,
                                   long sessions,
                                   Instant firstMemoryAt,
                                   Instant lastMemoryAt,
                                   Long vectorizationBacklog,
                                   ProfileImpact impact) {

  /**
   * Per-profile "Pieria impact": a lifetime, relative estimate (chars/4 heuristic) of the tokens
   * saved by answering from memory instead of re-feeding context. All token counts are raw — the
   * client derives the compression ratio, context-window count, and cost from these fields plus the
   * two display knobs.
   *
   * @param recalls             number of recalls served
   * @param tokensSavedEvidence headline saving: Σ (retrieved evidence − synthesized answer)
   * @param tokensSavedNaive    labelled upper bound: Σ (active corpus − synthesized answer)
   * @param tokensIngested      Σ raw-message tokens fed to ingest
   * @param tokensStored        Σ distilled-memory tokens produced from those messages
   * @param contextWindowTokens model context size used to express savings as a window count
   * @param pricePerMillionTokens price per 1M tokens for the cost line; {@code 0} hides it
   */
  public record ProfileImpact(long recalls,
                              long tokensSavedEvidence,
                              long tokensSavedNaive,
                              long tokensIngested,
                              long tokensStored,
                              int contextWindowTokens,
                              double pricePerMillionTokens) {
  }
}
