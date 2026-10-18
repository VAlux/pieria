package dev.alvo.pieria.daemon;

import dev.alvo.pieria.domain.error.ConflictException;
import dev.alvo.pieria.task.TaskRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Only the refusals are exercised: an accepted restart exits the JVM by design. That path needs a
 * manual check under launchd or systemd.
 */
class DaemonRestartServiceTests {

  private final TaskRegistry tasks = new TaskRegistry();
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void stop() {
    release.countDown();
    tasks.shutdown();
  }

  @Test
  void refusesWithoutAServiceManager() {
    DaemonRestartService service = new DaemonRestartService(new DaemonSupervisor(null), tasks, null);
    assertThatThrownBy(() -> service.requestRestart(true))
      .isInstanceOf(ConflictException.class)
      .hasMessageContaining("pieria daemon restart");
  }

  @Test
  void refusesWhileTasksRunUnlessForced() throws InterruptedException {
    CountDownLatch started = new CountDownLatch(1);
    tasks.submit("ingest", "p", progress -> {
      started.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return null;
    });
    started.await();
    DaemonRestartService service = new DaemonRestartService(new DaemonSupervisor(DaemonSupervisor.LAUNCHD), tasks, null);
    assertThatThrownBy(() -> service.requestRestart(false))
      .isInstanceOf(ConflictException.class)
      .hasMessageContaining("1 background task is still running");
  }
}
