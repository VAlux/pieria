package dev.alvo.pieria.harvester;

/** The process reading a harvester's output has gone away, so the run is over. */
public final class SinkClosedException extends RuntimeException {

  public SinkClosedException(Throwable cause) {
    super("harvester output is closed", cause);
  }
}
