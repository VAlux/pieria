package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.task.TaskSnapshot;
import dev.alvo.pieria.task.TaskRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only status surface for async daemon tasks. Clients submit work through an {@code /async}
 * endpoint, then poll {@code GET /v1/tasks/{taskId}} until the task reaches a terminal state.
 */
@RestController
@RequestMapping("/v1/tasks")
public class TaskController {

  private final TaskRegistry tasks;

  public TaskController(TaskRegistry tasks) {
    this.tasks = tasks;
  }

  @GetMapping("/{taskId}")
  public TaskStatusResponse status(@PathVariable String taskId) {
    UUID id = parse(taskId);
    TaskSnapshot s = tasks.find(id).orElseThrow(() -> NotFoundException.task(taskId));
    return new TaskStatusResponse(
      s.status().name(), s.phase(), s.done(), s.total(), s.errorKind(), s.errorMessage(), s.result());
  }

  private static UUID parse(String taskId) {
    try {
      return UUID.fromString(taskId);
    } catch (IllegalArgumentException e) {
      throw NotFoundException.task(taskId);
    }
  }
}
