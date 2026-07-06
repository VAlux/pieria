package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.api.response.TaskSubmitResponse;
import dev.alvo.pieria.onboarding.OnboardingService;
import dev.alvo.pieria.task.TaskRegistry;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Ingest an onboarding {@link SourceSpec} (markdown docs, source code, a web page) into a profile.
 * Discovery, reading, and fetching happen daemon-side on a background task, so any client — the CLI
 * or an MCP tool — need only name the source. The terminal task result carries an
 * {@link dev.alvo.pieria.onboarding.OnboardResult} the client renders as the "done" line.
 */
@RestController
@RequestMapping("/v1/profiles/{name}")
public class OnboardController {

  private final OnboardingService onboarding;
  private final TaskRegistry tasks;
  private final ObjectMapper objectMapper;

  public OnboardController(OnboardingService onboarding, TaskRegistry tasks, ObjectMapper objectMapper) {
    this.onboarding = onboarding;
    this.tasks = tasks;
    this.objectMapper = objectMapper;
  }

  /**
   * Start ingesting the source on a background task and return its id immediately so the client can
   * poll {@code GET /v1/tasks/{taskId}} for progress and the final result. {@code label} sets the
   * task's display name in {@code pieria task list} (default {@code "onboard"}).
   */
  @PostMapping("/onboard/async")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskSubmitResponse onboardAsync(@PathVariable String name,
                                         @RequestParam(name = "label", required = false) String label,
                                         @Valid @RequestBody SourceSpec spec) {
    String kind = label == null || label.isBlank() ? "onboard" : label;
    UUID taskId = tasks.submit(kind, name, progress ->
      objectMapper.valueToTree(onboarding.ingest(name, spec, progress)));
    return new TaskSubmitResponse(taskId.toString());
  }
}
