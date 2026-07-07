package dev.alvo.pieria.cli.command.task;

import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.client.TaskClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code pieria task kill <id>} — request cooperative cancellation of a running task. Cancellation
 * is best-effort: the task stops at its next checkpoint (or when an in-flight model call returns),
 * and any memories stored before that point remain.
 */
@Command(
  name = "kill",
  description = "Cancel a running task by id.",
  mixinStandardHelpOptions = true
)
public final class TaskKillCommand extends AbstractTaskCommand {

  @Parameters(index = "0", paramLabel = "<id>", description = "Task id (from 'pieria task list').")
  String taskId;

  @Override
  protected int run(TaskClient client) {
    TaskStatusResponse task = client.cancel(taskId);
    switch (task.status()) {
      case "RUNNING" ->
        log.info("Cancelling task {} — it will stop at its next checkpoint. Already-stored memories are kept.", taskId);
      case "CANCELLED" -> log.info("Task {} cancelled.", taskId);
      default ->
        log.info("Task {} had already finished ({}); nothing to cancel.", taskId, task.status());
    }
    return 0;
  }
}
