package dev.alvo.pieria.tools.os;

/**
 * Names the installers give the daemon's background service, shared by the CLI (which starts and
 * stops it) and the daemon (which recognises when it runs under one).
 */
public final class ServiceNames {

  /** launchd job label on macOS. */
  public static final String LAUNCHD_LABEL = "dev.alvo.pieria.daemon";

  /** systemd --user unit name (without the {@code .service} suffix) on Linux. */
  public static final String SYSTEMD_UNIT = "pieria-daemon";

  /** Environment variable the service definitions set to name their supervisor. */
  public static final String SUPERVISOR_ENV = "PIERIA_SUPERVISOR";

  private ServiceNames() {
  }
}
