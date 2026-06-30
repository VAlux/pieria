package dev.alvo.pieria.cli.command.task;

import dev.alvo.pieria.api.response.TaskListResponse;
import dev.alvo.pieria.api.response.TaskSummary;
import dev.alvo.pieria.cli.log.Durations;
import dev.alvo.pieria.cli.modules.task.HttpTaskClient;
import picocli.CommandLine.Command;

import java.util.List;

/**
 * {@code pieria task list} — show running and recently-finished daemon tasks with their id, kind,
 * profile, status, progress and (for running tasks) an ETA, so you can find a task you detached
 * from, copy its id to re-attach ({@code pieria task <id>}) or to {@code pieria task kill <id>}.
 */
@Command(
  name = "list",
  description = "List running and recently-finished daemon tasks.",
  mixinStandardHelpOptions = true
)
public final class TaskListCommand extends AbstractTaskCommand {

  @Override
  protected int run(HttpTaskClient client) {
    TaskListResponse response = client.list();
    List<TaskSummary> tasks = response.tasks();
    if (tasks == null || tasks.isEmpty()) {
      log.info("No tasks. (Finished tasks are kept for a few minutes; the daemon forgets them on restart.)");
      return 0;
    }

    log.info(String.format("%-36s  %-9s %-14s %-10s %-22s %s",
      "ID", "KIND", "PROFILE", "STATUS", "PROGRESS", "ETA"));
    for (TaskSummary t : tasks) {
      log.info(String.format("%-36s  %-9s %-14s %-10s %-22s %s",
        t.id(),
        nullToDash(t.kind()),
        nullToDash(t.profile()),
        t.status(),
        progress(t),
        eta(t)));
    }
    return 0;
  }

  /** {@code "verify 12/40 (30%)"}, or just the phase / a dash when no counts are known yet. */
  private static String progress(TaskSummary t) {
    String phase = t.phase() == null ? "—" : t.phase();
    if (t.total() <= 0) {
      return phase;
    }
    int pct = (int) Math.round(Math.min(1.0, (double) t.done() / t.total()) * 100);
    return phase + " " + t.done() + "/" + t.total() + " (" + pct + "%)";
  }

  /** Per-phase ETA from the observed rate since the phase started; {@code "—"} unless running. */
  private static String eta(TaskSummary t) {
    if (!"RUNNING".equals(t.status()) || t.total() <= 0 || t.done() <= 0 || t.phaseStartedAtEpochMs() <= 0) {
      return "—";
    }
    double elapsed = (System.currentTimeMillis() - t.phaseStartedAtEpochMs()) / 1000.0;
    if (elapsed <= 0) {
      return "—";
    }
    double rate = t.done() / elapsed;
    if (rate <= 0) {
      return "—";
    }
    return Durations.format(Math.round((t.total() - t.done()) / rate));
  }

  private static String nullToDash(String value) {
    return value == null || value.isBlank() ? "—" : value;
  }
}
