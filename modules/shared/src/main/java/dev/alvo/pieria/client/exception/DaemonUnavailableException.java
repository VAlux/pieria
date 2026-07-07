package dev.alvo.pieria.client.exception;

public final class DaemonUnavailableException extends DaemonClientException {
  public DaemonUnavailableException(String baseUrl, Throwable cause) {
    super("Pieria daemon is not reachable at " + baseUrl + ".", cause);
  }
}
