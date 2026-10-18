package dev.alvo.pieria.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DaemonSupervisorTests {

  @TempDir
  Path dir;

  @Test
  void declaredSupervisorWins() {
    assertEquals("launchd", DaemonSupervisor.detect(Map.of("PIERIA_SUPERVISOR", "launchd")::get, dir.resolve("none")));
    assertEquals("systemd", DaemonSupervisor.detect(Map.of("PIERIA_SUPERVISOR", "systemd")::get, dir.resolve("none")));
  }

  @Test
  void unknownDeclarationIsIgnored() {
    assertNull(DaemonSupervisor.detect(Map.of("PIERIA_SUPERVISOR", "cron")::get, dir.resolve("none")));
  }

  @Test
  void recognisesLaunchdJobByLabel() {
    assertEquals("launchd", DaemonSupervisor.detect(Map.of("XPC_SERVICE_NAME", "dev.alvo.pieria.daemon")::get, dir.resolve("none")));
    assertNull(DaemonSupervisor.detect(Map.of("XPC_SERVICE_NAME", "application.com.apple.Terminal")::get, dir.resolve("none")));
  }

  @Test
  void recognisesSystemdUnitFromCgroup() throws IOException {
    Path cgroup = Files.writeString(dir.resolve("cgroup"), "0::/user.slice/user-1000.slice/user@1000.service/app.slice/pieria-daemon.service\n");
    assertEquals("systemd", DaemonSupervisor.detect(Map.of("INVOCATION_ID", "abc")::get, cgroup));
    Path other = Files.writeString(dir.resolve("other"), "0::/user.slice/session-2.scope\n");
    assertNull(DaemonSupervisor.detect(Map.of("INVOCATION_ID", "abc")::get, other));
  }

  @Test
  void plainProcessCannotRestart() {
    DaemonSupervisor supervisor = new DaemonSupervisor(DaemonSupervisor.detect(Map.<String, String>of()::get, dir.resolve("none")));
    assertNull(supervisor.mechanism());
    assertEquals(false, supervisor.canRestart());
  }
}
