package dev.alvo.pieria.harvester.protocol;

/**
 * Data on the harvester wire does not follow the protocol: a malformed or oversized record, a
 * truncated stream, or a bad envelope.
 */
public final class ProtocolViolation extends RuntimeException {

  public ProtocolViolation(String message) {
    super(message);
  }

  public ProtocolViolation(String message, Throwable cause) {
    super(message, cause);
  }
}
