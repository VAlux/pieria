package dev.alvo.pieria.cli.log;

/**
 * Sink for per-phase progress updates emitted while polling a long-running daemon task. A client
 * calls {@link #onProgress} once per poll that observes forward progress; {@link ProgressReporter}
 * is the usual implementation, but tests may supply their own to assert the update sequence.
 */
@FunctionalInterface
public interface ProgressListener {

  /**
   * @param phase the daemon's current phase (e.g. {@code "extract"}, {@code "verify"}, {@code "store"},
   *              {@code "index"})
   * @param done  units completed so far in this phase
   * @param total total units in this phase
   */
  void onProgress(String phase, int done, int total);

  /** A listener that discards every update. */
  static ProgressListener noop() {
    return (phase, done, total) -> {
    };
  }
}
