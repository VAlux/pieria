package dev.alvo.pieria.ingestion;

/**
 * Sink for coarse pipeline progress so a long-running ingest can be observed while it runs.
 * The ingest pipeline calls {@link #onPhase} as each phase advances; a {@link #noop()} listener
 * makes the synchronous {@code /ingest} path behave exactly as before.
 *
 * <p>Phases are reported in order: {@code "extract"} (one tick per model call),
 * {@code "verify"} (one tick per candidate), {@code "store"} (one tick per stored candidate).
 */
@FunctionalInterface
public interface IngestProgressListener {

  /**
   * @param phase the current phase ({@code "extract"} | {@code "verify"} | {@code "store"})
   * @param done  units completed so far in this phase
   * @param total total units in this phase
   */
  void onPhase(String phase, int done, int total);

  /** A listener that discards every update. */
  static IngestProgressListener noop() {
    return (phase, done, total) -> {
    };
  }
}
