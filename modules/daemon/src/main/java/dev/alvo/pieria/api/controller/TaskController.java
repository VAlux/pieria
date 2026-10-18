package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.TaskLaneProgress;
import dev.alvo.pieria.api.response.TaskListResponse;
import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.api.response.TaskSubmitResponse;
import dev.alvo.pieria.api.response.TaskSummary;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.task.TaskRegistry.TaskInfo;
import dev.alvo.pieria.task.TaskResultSummaries;
import dev.alvo.pieria.task.TaskService;
import dev.alvo.pieria.task.TaskSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Status and control surface for async daemon tasks. Clients submit work through an {@code /async}
 * endpoint, then poll {@code GET /v1/tasks/{taskId}} until terminal, list in-flight and
 * recently-finished tasks with {@code GET /v1/tasks} (optionally {@code ?profile=}), cancel a
 * running task with {@code DELETE /v1/tasks/{taskId}}, and start a failed or cancelled one again
 * with {@code POST /v1/tasks/{taskId}/retry} while it is still listed.
 */
@RestController
@RequestMapping("/v1/tasks")
public class TaskController {

  private final TaskService tasks;

  public TaskController(TaskService tasks) {
    this.tasks = tasks;
  }

  private static TaskSummary toSummary(TaskInfo info) {
    TaskSnapshot s = info.snapshot();
    return new TaskSummary(
      info.id().toString(), info.kind(), info.profile(), s.status().name(), lanes(s),
      epochMs(s.startedAt()), s.errorKind(), s.errorMessage(), epochMs(s.finishedAt()), durationMs(s),
      TaskResultSummaries.summarize(s.result()), info.retryable());
  }

  private static TaskStatusResponse toStatus(TaskInfo info) {
    TaskSnapshot s = info.snapshot();
    return new TaskStatusResponse(
      s.status().name(), info.kind(), info.profile(), lanes(s), epochMs(s.startedAt()),
      s.errorKind(), s.errorMessage(), s.result(), epochMs(s.finishedAt()), durationMs(s),
      TaskResultSummaries.summarize(s.result()), info.retryable());
  }

  private static List<TaskLaneProgress> lanes(TaskSnapshot snapshot) {
    return snapshot.lanes().stream().map(lane -> new TaskLaneProgress(
      lane.name(), lane.state().name(), lane.phase(), lane.done(), lane.total(),
      epochMs(lane.phaseStartedAt()))).toList();
  }

  private static long epochMs(Instant instant) {
    return instant == null ? 0L : instant.toEpochMilli();
  }

  /** Elapsed so far for a running task, total for a finished one. */
  private static long durationMs(TaskSnapshot s) {
    Instant end = s.finishedAt() == null ? Instant.now() : s.finishedAt();
    return s.startedAt() == null ? 0L : Math.max(0L, end.toEpochMilli() - s.startedAt().toEpochMilli());
  }

  private static UUID parse(String taskId) {
    try {
      return UUID.fromString(taskId);
    } catch (IllegalArgumentException e) {
      throw NotFoundException.task(taskId);
    }
  }

  @GetMapping
  public TaskListResponse list(@RequestParam(name = "profile", required = false) String profile) {
    String filter = profile == null || profile.isBlank() ? null : profile;
    return new TaskListResponse(tasks.list(filter).stream().map(TaskController::toSummary).toList());
  }

  @GetMapping("/{taskId}")
  public TaskStatusResponse status(@PathVariable String taskId) {
    return toStatus(tasks.get(parse(taskId)));
  }

  /**
   * Request cooperative cancellation. An unknown id is a 404; cancelling an already-finished task is
   * a no-op that still returns its (terminal) snapshot, so the client can report the real outcome.
   */
  @DeleteMapping("/{taskId}")
  public TaskStatusResponse cancel(@PathVariable String taskId) {
    return toStatus(tasks.cancel(parse(taskId)));
  }

  /**
   * Start a failed or cancelled task again (409 otherwise, 404 once evicted). The body is ignored
   * but required: a JSON body makes browsers preflight the call, so another site cannot fire it.
   */
  @PostMapping("/{taskId}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskSubmitResponse retry(@PathVariable String taskId, @RequestBody JsonNode ignored) {
    return new TaskSubmitResponse(tasks.retry(parse(taskId)).toString());
  }
}
