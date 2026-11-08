package dev.alvo.pieria.harvester;

import dev.alvo.pieria.harvester.protocol.ErrorClass;

/** A failure the harvester understands, reported to the daemon as an {@code error} record. */
public final class HarvesterException extends Exception {

  private final ErrorClass errorClass;
  private final Long retryAfterSeconds;

  public HarvesterException(ErrorClass errorClass, String message) {
    this(errorClass, message, null);
  }

  public HarvesterException(ErrorClass errorClass, String message, Long retryAfterSeconds) {
    super(message);
    this.errorClass = errorClass;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public ErrorClass errorClass() {
    return errorClass;
  }

  public Long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
