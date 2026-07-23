package dev.alvo.pieria.cli.modules.task;

import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.cli.log.ProgressListener;
import dev.alvo.pieria.client.TaskClient;
import dev.alvo.pieria.client.exception.DaemonInterruptedException;

public final class TaskPoller {
  private final TaskClient tasks;

  public TaskPoller(TaskClient tasks) {
    this.tasks = tasks;
  }

  public TaskStatusResponse await(String taskId, ProgressListener progress) {
    while (true) {
      TaskStatusResponse task = tasks.status(taskId);
      if (!"RUNNING".equals(task.status())) {
        return task;
      }
      progress.onProgress(task.lanes());
      try {
        Thread.sleep(1_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new DaemonInterruptedException(e);
      }
    }
  }
}
