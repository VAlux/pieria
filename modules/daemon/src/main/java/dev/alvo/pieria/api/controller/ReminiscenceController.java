package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.OrphanCountResponse;
import dev.alvo.pieria.api.response.TaskSubmitResponse;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.reminiscence.ReminiscenceService;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.task.TaskRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Orphan adoption ("reminiscence"): weave edgeless memories into the entity-relation graph by
 * retroactively running the ingest graph-extraction over their content. The adoption itself runs on
 * a background task (model-heavy); the terminal task result carries a
 * {@link dev.alvo.pieria.reminiscence.ReminiscenceResult} the client renders as the "done" line.
 */
@RestController
@RequestMapping("/v1/profiles/{name}")
public class ReminiscenceController {

  private final ReminiscenceService reminiscence;
  private final MemoryStore store;
  private final TaskRegistry tasks;
  private final ObjectMapper objectMapper;

  public ReminiscenceController(ReminiscenceService reminiscence, MemoryStore store, TaskRegistry tasks,
                                ObjectMapper objectMapper) {
    this.reminiscence = reminiscence;
    this.store = store;
    this.tasks = tasks;
    this.objectMapper = objectMapper;
  }

  /**
   * Start orphan adoption on a background task and return its id immediately so the client can poll
   * {@code GET /v1/tasks/{taskId}} for progress and the final result. {@code label} sets the task's
   * display name in {@code pieria task list} (default {@code "reminisce"}).
   */
  @PostMapping("/reminisce/async")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskSubmitResponse reminisceAsync(@PathVariable String name,
                                           @RequestParam(name = "label", required = false) String label) {
    String kind = label == null || label.isBlank() ? "reminisce" : label;
    UUID taskId = tasks.submit(kind, name, progress ->
      objectMapper.valueToTree(reminiscence.adoptOrphans(name, progress)));
    return new TaskSubmitResponse(taskId.toString());
  }

  /**
   * Cheap dry-run: how many orphans a run would adopt, via a plain store count (no model call).
   */
  @GetMapping("/reminisce/orphans")
  public OrphanCountResponse orphans(@PathVariable String name) {
    var profile = store.findProfile(name).orElseThrow(() -> NotFoundException.profile(name));
    return new OrphanCountResponse(store.countGraphOrphans(profile.id()));
  }
}
