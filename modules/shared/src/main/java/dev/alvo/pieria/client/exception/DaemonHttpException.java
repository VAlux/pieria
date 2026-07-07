package dev.alvo.pieria.client.exception;

public class DaemonHttpException extends DaemonClientException {
  private final int status;
  private final String body;
  private final String daemonMessage;

  public DaemonHttpException(int status, String body, String daemonMessage) {
    super("daemon returned HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body));
    this.status = status;
    this.body = body == null ? "" : body;
    this.daemonMessage = daemonMessage;
  }

  public int status() {
    return status;
  }

  public String body() {
    return body;
  }

  public String daemonMessage() {
    return daemonMessage;
  }
}
