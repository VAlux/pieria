package dev.alvo.pieria.domain.profile;

/**
 * Per-profile lifetime token-savings counters, accumulated at event time in the {@code
 * profile_usage} table. All token figures are the shared {@code chars/4} heuristic — a relative
 * estimate, not billing-grade accounting.
 *
 * @param recallCount        number of recalls served
 * @param ingestCount        number of ingests run
 * @param tokensSaved        Σ over recalls of (source tokens behind the evidence − answer tokens):
 *                           what re-reading the material each answer was distilled from would cost
 * @param tokensRecallServed Σ synthesized-answer tokens served (informational)
 * @param tokensIngested     Σ raw-message tokens fed to ingest
 * @param tokensStored       Σ distilled-memory tokens produced from those messages
 */
public record ProfileUsage(long recallCount,
                           long ingestCount,
                           long tokensSaved,
                           long tokensRecallServed,
                           long tokensIngested,
                           long tokensStored) {

  private static final ProfileUsage EMPTY = new ProfileUsage(0, 0, 0, 0, 0, 0);

  /**
   * The zero-usage record, returned when a profile has no {@code profile_usage} row yet.
   */
  public static ProfileUsage empty() {
    return EMPTY;
  }
}
