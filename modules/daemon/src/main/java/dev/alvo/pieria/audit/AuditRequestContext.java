package dev.alvo.pieria.audit;

/**
 * Request correlation inherited by asynchronous tasks submitted during a profile API call.
 */
public record AuditRequestContext(String profileName, String operation, String requestId, AuditCaller caller) {
  private static final ThreadLocal<AuditRequestContext> CURRENT = new ThreadLocal<>();

  static void set(AuditRequestContext context) {
    CURRENT.set(context);
  }

  static void clear() {
    CURRENT.remove();
  }

  public static AuditRequestContext current() {
    return CURRENT.get();
  }

  /**
   * Makes {@code context} current on this thread until the returned scope closes, restoring what
   * was there before. Background work uses it so tasks it submits inherit the originating request.
   */
  public static Scope bind(AuditRequestContext context) {
    AuditRequestContext previous = CURRENT.get();
    CURRENT.set(context);
    return () -> {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    };
  }

  /** A binding made by {@link #bind}; closing it never throws. */
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
