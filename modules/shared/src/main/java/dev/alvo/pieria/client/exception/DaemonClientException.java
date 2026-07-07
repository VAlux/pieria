package dev.alvo.pieria.client.exception;

public class DaemonClientException extends RuntimeException {
  public DaemonClientException(String message) {
    super(message);
  }

  public DaemonClientException(String message, Throwable cause) {
    super(message, cause);
  }
}
