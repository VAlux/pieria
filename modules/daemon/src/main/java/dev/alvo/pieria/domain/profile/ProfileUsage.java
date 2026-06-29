package dev.alvo.pieria.domain.profile;

/**
 * Per-profile lifetime token-savings counters, accumulated at event time in the {@code
 * profile_usage} table. All token figures are the shared {@code chars/4} heuristic — a relative
 * estimate of what Pieria saved by answering from memory instead of re-feeding context.
 *
 * @param recallCount          number of recalls served
 * @param ingestCount          number of ingests run
 * @param tokensSavedEvidence  headline savings: Σ over recalls of (evidence tokens − answer tokens)
 * @param tokensSavedNaive     labelled upper bound: Σ over recalls of (active corpus − answer tokens)
 * @param tokensRecallServed   Σ synthesized-answer tokens served (informational)
 * @param tokensIngested       Σ raw-message tokens fed to ingest
 * @param tokensStored         Σ distilled-memory tokens produced from those messages
 */
public record ProfileUsage(long recallCount,
                           long ingestCount,
                           long tokensSavedEvidence,
                           long tokensSavedNaive,
                           long tokensRecallServed,
                           long tokensIngested,
                           long tokensStored) {

  private static final ProfileUsage EMPTY = new ProfileUsage(0, 0, 0, 0, 0, 0, 0);

  /** The zero-usage record, returned when a profile has no {@code profile_usage} row yet. */
  public static ProfileUsage empty() {
    return EMPTY;
  }
}
