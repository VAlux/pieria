package dev.alvo.pieria.cli.command.task;

import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.cli.log.ProgressReporter;
import dev.alvo.pieria.cli.modules.task.HttpTaskClient;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/**
 * {@code pieria task} — inspect and control long-running daemon tasks (onboarding ingest, code
 * indexing). With no argument it prints usage; with a task id it re-attaches to that task's live
 * progress exactly as if you'd never detached. Sub-commands {@code list} and {@code kill} cover the
 * other operations.
 *
 * <p>Re-attach is just the original monitoring pointed at an existing task: the same 1-second poll
 * of {@code /v1/tasks/{id}} rendered by the same {@link ProgressReporter}. Press ctrl+C to detach
 * again without affecting the task.
 */
@Command(
  name = "task",
  description = "Inspect and control running daemon tasks.",
  mixinStandardHelpOptions = true,
  subcommands = {TaskListCommand.class, TaskKillCommand.class}
)
public final class TaskCommand extends AbstractTaskCommand {

  @Parameters(index = "0", arity = "0..1", paramLabel = "<id>",
    description = "Re-attach to a running task's live progress by id (from 'pieria task list').")
  String taskId;

  @Override
  protected int run(HttpTaskClient client) {
    if (taskId == null || taskId.isBlank()) {
      CommandLine.usage(this, System.out);
      return 0;
    }

    ProgressReporter reporter = new ProgressReporter();
    TaskStatusResponse terminal = client.attach(taskId, reporter);
    reporter.finish();
    return reportTerminal(terminal);
  }

  private int reportTerminal(TaskStatusResponse task) {
    return switch (task.status()) {
      case "SUCCEEDED" -> {
        JsonNode count = task.result() == null ? null : task.result().get("count");
        if (count != null) {
          log.info("Task {} succeeded. Stored {} memor{}.",
            taskId, count.asInt(0), count.asInt(0) == 1 ? "y" : "ies");
        } else {
          log.info("Task {} succeeded.", taskId);
        }
        yield 0;
      }
      case "CANCELLED" -> {
        log.info("Task {} was cancelled.", taskId);
        yield 0;
      }
      default -> {
        log.error("Task {} failed: {}", taskId,
          task.errorMessage() == null ? task.errorKind() : task.errorMessage());
        yield 1;
      }
    };
  }
}
