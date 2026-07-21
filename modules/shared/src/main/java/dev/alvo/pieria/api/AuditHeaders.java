package dev.alvo.pieria.api;

/** Stable caller-attribution and correlation headers understood by the Pieria daemon. */
public final class AuditHeaders {

  public static final String CLIENT = "X-Pieria-Client";
  public static final String HARNESS = "X-Pieria-Harness";
  public static final String CHANNEL = "X-Pieria-Channel";
  public static final String CLIENT_VERSION = "X-Pieria-Client-Version";
  public static final String REQUEST_ID = "X-Pieria-Request-Id";

  private AuditHeaders() {
  }
}
