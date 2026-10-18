package dev.alvo.pieria.daemon;

import dev.alvo.pieria.tools.os.ServiceNames;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Works out whether the daemon runs under a service manager that relaunches it after a non-zero
 * exit. Only then can the console restart it: the daemon exits and launchd ({@code KeepAlive
 * SuccessfulExit=false}) or systemd ({@code Restart=on-failure}) starts it again. A daemon spawned
 * by hand, or by the Windows scheduled task, would simply stay down.
 */
@Component
public class DaemonSupervisor {

  public static final String LAUNCHD = "launchd";
  public static final String SYSTEMD = "systemd";

  private final String mechanism;

  public DaemonSupervisor() {
    this(detect(System::getenv, Path.of("/proc/self/cgroup")));
  }

  DaemonSupervisor(String mechanism) {
    this.mechanism = mechanism;
  }

  /** {@code launchd}, {@code systemd}, or {@code null} when nothing would relaunch the daemon. */
  public String mechanism() {
    return mechanism;
  }

  public boolean canRestart() {
    return mechanism != null;
  }

  /**
   * Prefers the marker the installers write into the service definition; falls back to what each
   * service manager sets on its own for installs made before that marker existed.
   */
  static String detect(Function<String, String> env, Path cgroupFile) {
    String declared = env.apply(ServiceNames.SUPERVISOR_ENV);
    if (LAUNCHD.equals(declared) || SYSTEMD.equals(declared)) return declared;
    if (ServiceNames.LAUNCHD_LABEL.equals(env.apply("XPC_SERVICE_NAME"))) return LAUNCHD;
    if (env.apply("INVOCATION_ID") != null && inSystemdUnit(cgroupFile)) return SYSTEMD;
    return null;
  }

  private static boolean inSystemdUnit(Path cgroupFile) {
    try {
      return Files.isReadable(cgroupFile)
        && Files.readString(cgroupFile).contains(ServiceNames.SYSTEMD_UNIT + ".service");
    } catch (IOException e) {
      return false;
    }
  }
}
