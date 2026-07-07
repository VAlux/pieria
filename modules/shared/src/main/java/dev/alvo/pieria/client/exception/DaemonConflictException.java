package dev.alvo.pieria.client.exception;

public final class DaemonConflictException extends DaemonHttpException {
  public DaemonConflictException(String body, String message) {
    super(409, body, message);
  }
}
