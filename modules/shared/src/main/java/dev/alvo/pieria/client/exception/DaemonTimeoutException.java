package dev.alvo.pieria.client.exception;

public final class DaemonTimeoutException extends DaemonClientException {
  public DaemonTimeoutException(String baseUrl, Throwable cause) {
    super("Pieria daemon request timed out at " + baseUrl + ".", cause);
  }
}
