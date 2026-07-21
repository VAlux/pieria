package dev.alvo.pieria.audit;

/** Request correlation inherited by asynchronous tasks submitted during a profile API call. */
public record AuditRequestContext(String profileName, String operation, String requestId, AuditCaller caller) {
  private static final ThreadLocal<AuditRequestContext> CURRENT = new ThreadLocal<>();

  static void set(AuditRequestContext context) { CURRENT.set(context); }
  static void clear() { CURRENT.remove(); }
  public static AuditRequestContext current() { return CURRENT.get(); }
}
