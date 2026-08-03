package dev.alvo.pieria.ingestion;

/**
 * How one ingest should use the chunk-extraction ledger (see {@link IngestionService}).
 *
 * <p>Callers pick the mode from how the ingest was triggered; the
 * {@code pieria.ingestion.chunk-ledger-enabled} kill switch can still force {@link #DISABLED}.
 */
public enum ChunkLedgerMode {

  /**
   * No ledger: every chunk goes through the full model pipeline. Used by bulk onboarding, which
   * already dedupes at the document level and whose per-batch chunk indices would otherwise
   * collide within its single fixed session id.
   */
  DISABLED,

  /**
   * Ledger on, every pending chunk extracted — including the trailing partial one. The right mode
   * for a final capture (session end, pre-compaction) and for direct API ingests.
   */
  ENABLED,

  /**
   * Ledger on, and the trailing partial chunk is left for later. It is the only chunk that can
   * still grow, so it changes on every turn and would miss the ledger every time; a routine
   * mid-session capture skips it and lets a boundary crossing or the final flush pick it up.
   */
  DEFER_TRAILING
}
