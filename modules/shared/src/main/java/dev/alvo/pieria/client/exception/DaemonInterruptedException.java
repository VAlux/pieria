package dev.alvo.pieria.client.exception;

public final class DaemonInterruptedException extends DaemonClientException {
  public DaemonInterruptedException(Throwable cause) {
    super("daemon request was interrupted", cause);
  }
}
