package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.TaskListResponse;
import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.api.response.TaskSummary;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.task.TaskRegistry;
import dev.alvo.pieria.task.TaskRegistry.CancelOutcome;
import dev.alvo.pieria.task.TaskRegistry.TaskInfo;
import dev.alvo.pieria.task.TaskSnapshot;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Status and control surface for async daemon tasks. Clients submit work through an {@code /async}
 * endpoint, then poll {@code GET /v1/tasks/{taskId}} until terminal, list in-flight and
 * recently-finished tasks with {@code GET /v1/tasks}, and cancel a running task with
 * {@code DELETE /v1/tasks/{taskId}}.
 */
@RestController
@RequestMapping("/v1/tasks")
public class TaskController {

  private final TaskRegistry tasks;

  public TaskController(TaskRegistry tasks) {
    this.tasks = tasks;
  }

  @GetMapping
  public TaskListResponse list() {
    List<TaskSummary> summaries = tasks.all().stream().map(TaskController::toSummary).toList();
    return new TaskListResponse(summaries);
  }

  @GetMapping("/{taskId}")
  public TaskStatusResponse status(@PathVariable String taskId) {
    UUID id = parse(taskId);
    TaskInfo info = find(id, taskId);
    TaskSnapshot s = info.snapshot();
    return new TaskStatusResponse(
      s.status().name(), info.kind(), info.profile(), s.phase(), s.done(), s.total(),
      epochMs(s.startedAt()), epochMs(s.phaseStartedAt()), s.errorKind(), s.errorMessage(), s.result());
  }

  /**
   * Request cooperative cancellation. An unknown id is a 404; cancelling an already-finished task is
   * a no-op that still returns its (terminal) snapshot, so the client can report the real outcome.
   */
  @DeleteMapping("/{taskId}")
  public TaskStatusResponse cancel(@PathVariable String taskId) {
    UUID id = parse(taskId);
    CancelOutcome outcome = tasks.cancel(id);
    if (outcome == CancelOutcome.NOT_FOUND) {
      throw NotFoundException.task(taskId);
    }
    return status(taskId);
  }

  private TaskInfo find(UUID id, String taskId) {
    return tasks.all().stream()
      .filter(i -> i.id().equals(id))
      .findFirst()
      .orElseThrow(() -> NotFoundException.task(taskId));
  }

  private static TaskSummary toSummary(TaskInfo info) {
    TaskSnapshot s = info.snapshot();
    return new TaskSummary(
      info.id().toString(), info.kind(), info.profile(), s.status().name(), s.phase(),
      s.done(), s.total(), epochMs(s.startedAt()), epochMs(s.phaseStartedAt()),
      s.errorKind(), s.errorMessage());
  }

  private static long epochMs(Instant instant) {
    return instant == null ? 0L : instant.toEpochMilli();
  }

  private static UUID parse(String taskId) {
    try {
      return UUID.fromString(taskId);
    } catch (IllegalArgumentException e) {
      throw NotFoundException.task(taskId);
    }
  }
}
