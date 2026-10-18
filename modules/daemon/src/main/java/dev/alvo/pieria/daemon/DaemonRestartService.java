package dev.alvo.pieria.daemon;

import dev.alvo.pieria.domain.error.ConflictException;
import dev.alvo.pieria.task.TaskRegistry;
import dev.alvo.pieria.task.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

/**
 * Restarts the daemon on request from the console. The daemon cannot start itself again, so this
 * exits with {@link #RESTART_EXIT_CODE} and relies on the service manager: the launchd job
 * ({@code KeepAlive SuccessfulExit=false}) and the systemd unit ({@code Restart=on-failure}) both
 * relaunch a daemon that exits non-zero. Anywhere else the request is refused, because the daemon
 * would simply stay down.
 */
@Service
public class DaemonRestartService {

  /** EX_TEMPFAIL: "try again", which is what a restart is. Non-zero so both supervisors relaunch. */
  static final int RESTART_EXIT_CODE = 75;

  /** Time for the HTTP response to flush before the context starts closing. */
  private static final long EXIT_DELAY_MS = 300;

  private static final Logger log = LoggerFactory.getLogger(DaemonRestartService.class);

  private final DaemonSupervisor supervisor;
  private final TaskRegistry tasks;
  private final ApplicationContext context;

  public DaemonRestartService(DaemonSupervisor supervisor, TaskRegistry tasks, ApplicationContext context) {
    this.supervisor = supervisor;
    this.tasks = tasks;
    this.context = context;
  }

  /** A restart that was scheduled: who will bring the daemon back, and roughly when. */
  public record Scheduled(String mechanism, long expectedDowntimeMs) {
  }

  public Scheduled requestRestart(boolean force) {
    if (!supervisor.canRestart()) {
      throw new ConflictException("This daemon is not running under launchd or systemd, so it cannot restart itself."
        + " Run `pieria daemon restart` in a terminal.");
    }
    long running = tasks.all().stream().filter(t -> t.snapshot().status() == TaskStatus.RUNNING).count();
    if (running > 0 && !force) {
      throw new ConflictException(running + (running == 1 ? " background task is" : " background tasks are")
        + " still running; restarting would interrupt them. Wait, or restart anyway.");
    }
    log.info("restart requested; exiting with {} for {} to relaunch", RESTART_EXIT_CODE, supervisor.mechanism());
    Thread.ofVirtual().name("pieria-restart").start(() -> {
      try {
        Thread.sleep(EXIT_DELAY_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      System.exit(SpringApplication.exit(context, () -> RESTART_EXIT_CODE));
    });
    long downtime = DaemonSupervisor.SYSTEMD.equals(supervisor.mechanism()) ? 8_000 : 4_000;
    return new Scheduled(supervisor.mechanism(), downtime);
  }
}
