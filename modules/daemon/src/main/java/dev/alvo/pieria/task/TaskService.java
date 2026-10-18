package dev.alvo.pieria.task;

import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.task.TaskRegistry.CancelOutcome;
import dev.alvo.pieria.task.TaskRegistry.TaskInfo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Use cases behind {@code /v1/tasks}: list (optionally one profile's), inspect, cancel and retry. */
@Service
public class TaskService {

  private final TaskRegistry tasks;

  public TaskService(TaskRegistry tasks) {
    this.tasks = tasks;
  }

  /** Running and recently finished tasks, newest first; {@code profile} null lists every profile's. */
  public List<TaskInfo> list(String profile) {
    return tasks.all().stream().filter(info -> profile == null || profile.equals(info.profile())).toList();
  }

  public TaskInfo get(UUID id) {
    return tasks.findInfo(id).orElseThrow(() -> NotFoundException.task(id.toString()));
  }

  /** Requests cancellation; cancelling a finished task is a no-op that reports its real outcome. */
  public TaskInfo cancel(UUID id) {
    if (tasks.cancel(id) == CancelOutcome.NOT_FOUND) {
      throw NotFoundException.task(id.toString());
    }
    return get(id);
  }

  /** Starts the failed or cancelled task's work again and returns the new task's id. */
  public UUID retry(UUID id) {
    return tasks.retry(id);
  }
}
