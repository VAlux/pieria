package dev.alvo.pieria.api.response;

import java.time.Instant;
import java.util.List;
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
 * @param spend                lifetime real inference-token spend by tier, or {@code null} if none
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
                                   ProfileImpact impact,
                                   ProfileSpend spend) {

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

  /**
   * Per-profile "inference spend": the real provider token usage Pieria incurred running the model
   * pipeline, broken down by model tier (extraction / synthesis / embedding). Tokens are the
   * provider-reported prompt/completion counts (not the chars/4 heuristic). Per-tier {@code costUsd}
   * is pre-computed server-side from the configured per-tier input/output prices; {@code costAvailable}
   * is {@code true} only when at least one tier has a non-zero price configured, so the client knows
   * whether to render the cost line.
   *
   * @param tiers                 per-tier spend, in tier order; empty tiers are omitted
   * @param totalPromptTokens     Σ prompt (input) tokens across tiers
   * @param totalCompletionTokens Σ completion (output) tokens across tiers
   * @param totalCostUsd          Σ per-tier cost; {@code 0} when no prices are configured
   * @param costAvailable         whether any tier has a configured price (controls cost display)
   */
  public record ProfileSpend(List<TierSpend> tiers,
                             long totalPromptTokens,
                             long totalCompletionTokens,
                             double totalCostUsd,
                             boolean costAvailable) {

    /**
     * One model tier's spend.
     *
     * @param tier             tier name, lower-case ("extraction" | "synthesis" | "embedding")
     * @param calls            number of model calls recorded for the tier
     * @param promptTokens     provider-reported input tokens
     * @param completionTokens provider-reported output tokens
     * @param costUsd          cost for this tier from its configured input/output prices
     */
    public record TierSpend(String tier,
                            long calls,
                            long promptTokens,
                            long completionTokens,
                            double costUsd) {
    }
  }
}
