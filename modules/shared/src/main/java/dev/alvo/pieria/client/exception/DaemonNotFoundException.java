package dev.alvo.pieria.client.exception;

public final class DaemonNotFoundException extends DaemonHttpException {
  public DaemonNotFoundException(String body, String message) {
    super(404, body, message);
  }
}
