package dev.alvo.pieria.cli.command.task;

import dev.alvo.pieria.api.response.TaskLaneProgress;
import dev.alvo.pieria.api.response.TaskListResponse;
import dev.alvo.pieria.api.response.TaskSummary;
import dev.alvo.pieria.cli.log.Durations;
import dev.alvo.pieria.client.TaskClient;
import picocli.CommandLine.Command;

import java.util.List;

/** {@code pieria task list} — show all task lanes in a compact ordered progress column. */
@Command(name = "list", description = "List running and recently-finished daemon tasks.",
  mixinStandardHelpOptions = true)
public final class TaskListCommand extends AbstractTaskCommand {

  @Override
  protected int run(TaskClient client) {
    TaskListResponse response = client.list();
    List<TaskSummary> tasks = response.tasks();
    if (tasks == null || tasks.isEmpty()) {
      log.info("No tasks. (Finished tasks are kept for a few minutes; the daemon forgets them on restart.)");
      return 0;
    }
    log.info(String.format("%-36s  %-9s %-14s %-10s %s", "ID", "KIND", "PROFILE", "STATUS", "LANES"));
    for (TaskSummary task : tasks) {
      log.info(String.format("%-36s  %-9s %-14s %-10s %s", task.id(), nullToDash(task.kind()),
        nullToDash(task.profile()), task.status(), progress(task)));
    }
    return 0;
  }

  private static String progress(TaskSummary task) {
    if (task.lanes() == null || task.lanes().isEmpty()) {
      return "—";
    }
    return task.lanes().stream().map(TaskListCommand::laneProgress)
      .collect(java.util.stream.Collectors.joining(" | "));
  }

  private static String laneProgress(TaskLaneProgress lane) {
    if ("code".equals(lane.name()) && "WAITING".equals(lane.state())
        && "waiting for content".equals(lane.phase())) {
      return "code:waiting for content";
    }
    String phase = lane.phase() == null ? lane.state().toLowerCase() : lane.phase();
    if (lane.total() <= 0) {
      return lane.name() + ':' + phase;
    }
    int pct = (int) Math.round(Math.min(1.0, (double) lane.done() / lane.total()) * 100);
    String eta = eta(lane);
    return lane.name() + ':' + phase + ' ' + lane.done() + '/' + lane.total()
      + " (" + pct + "%)" + (eta.equals("—") ? "" : " ETA " + eta);
  }

  private static String eta(TaskLaneProgress lane) {
    if (!"RUNNING".equals(lane.state()) || lane.total() <= 0 || lane.done() <= 0
        || lane.phaseStartedAtEpochMs() <= 0) {
      return "—";
    }
    double elapsed = (System.currentTimeMillis() - lane.phaseStartedAtEpochMs()) / 1_000.0;
    double rate = elapsed > 0 ? lane.done() / elapsed : 0;
    return rate <= 0 ? "—" : Durations.format(Math.round((lane.total() - lane.done()) / rate));
  }

  private static String nullToDash(String value) {
    return value == null || value.isBlank() ? "—" : value;
  }
}
