package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.TaskSubmitResponse;
import dev.alvo.pieria.api.response.WikiResponse;
import dev.alvo.pieria.wiki.WikiBuildService;
import dev.alvo.pieria.wiki.WikiException;
import dev.alvo.pieria.wiki.WikiService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Whole-wiki operations for the console: overall status and a build that writes every article. */
@RestController
@RequestMapping("/v1/profiles/{name}/wiki")
public class WikiBuildController {

  private final WikiService wiki;
  private final WikiBuildService builds;

  public WikiBuildController(WikiService wiki, WikiBuildService builds) {
    this.wiki = wiki;
    this.builds = builds;
  }

  @GetMapping("/status")
  public WikiResponse.Status status(@PathVariable String name) {
    return wiki.status(name);
  }

  /** Runs as a background task ({@code wiki-build}); follow it through {@code /v1/tasks}. */
  @PostMapping("/build")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskSubmitResponse build(@PathVariable String name, @RequestBody WikiResponse.BuildRequest request) {
    return new TaskSubmitResponse(builds.start(name, request.scope()).toString());
  }

  @ExceptionHandler(WikiException.class)
  public ResponseEntity<?> error(WikiException e) {
    return ResponseEntity.status(e.status()).header("Cache-Control", "no-store").body(Map.of("code", e.getMessage()));
  }
}
