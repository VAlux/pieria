package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.request.CodeIndexRequest;
import dev.alvo.pieria.api.response.CodeIndexResponse;
import dev.alvo.pieria.api.response.CodeStatusResponse;
import dev.alvo.pieria.api.response.TaskSubmitResponse;
import dev.alvo.pieria.code.CodeIndexingService;
import dev.alvo.pieria.code.CodeIndexingService.CodeIndexSummary;
import dev.alvo.pieria.code.CodeIndexingService.SourceFile;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.storage.CodeIndexStore;
import dev.alvo.pieria.storage.CodeIndexStore.CodeIndexCounts;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.task.TaskRegistry;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/**
 * index a batch of source files and read code-index status. Indexing is deterministic and synchronous (no model call);
 * vectorization of derived memories continues asynchronously through the existing outbox.
 */
@RestController
@RequestMapping("/v1/profiles/{name}")
public class CodeController {

  private static final CodeStatusResponse EMPTY_STATUS =
    new CodeStatusResponse(false, 0, 0, 0, 0, 0);

  private final CodeIndexingService indexing;
  private final CodeIndexStore codeStore;
  private final MemoryStore store;
  private final ObjectMapper objectMapper;
  private final TaskRegistry tasks;

  public CodeController(CodeIndexingService indexing, CodeIndexStore codeStore, MemoryStore store,
                        ObjectMapper objectMapper, TaskRegistry tasks) {
    this.indexing = indexing;
    this.codeStore = codeStore;
    this.store = store;
    this.objectMapper = objectMapper;
    this.tasks = tasks;
  }

  @PostMapping("/code")
  public CodeIndexResponse index(@PathVariable String name, @Valid @RequestBody CodeIndexRequest request) {
    List<SourceFile> files = toSourceFiles(request);

    CodeIndexSummary summary = indexing.index(name, request.treeHash(), files);

    return toResponse(summary);
  }

  /**
   * Async variant of {@link #index}: start indexing on a background task and return its id
   * immediately so the client can poll {@code GET /v1/tasks/{taskId}} and render progress. The
   * terminal result carries the full {@link CodeIndexResponse} summary.
   */
  @PostMapping("/code/async")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskSubmitResponse indexAsync(@PathVariable String name, @Valid @RequestBody CodeIndexRequest request) {
    List<SourceFile> files = toSourceFiles(request);

    UUID taskId = tasks.submit(progress -> {
      CodeIndexSummary summary = indexing.index(name, request.treeHash(), files, progress);
      return objectMapper.valueToTree(toResponse(summary));
    });
    return new TaskSubmitResponse(taskId.toString());
  }

  private static List<SourceFile> toSourceFiles(CodeIndexRequest request) {
    return request.files().stream()
      .map(f -> new SourceFile(f.repoRelPath(), f.language(), f.contentHash(), f.content()))
      .toList();
  }

  private static CodeIndexResponse toResponse(CodeIndexSummary summary) {
    return new CodeIndexResponse(
      summary.filesReceived(),
      summary.filesSkippedUnchanged(),
      summary.filesParsed(),
      summary.filesFailed(),
      summary.symbols(),
      summary.resolvedEdges(),
      summary.heuristicEdges(),
      summary.memoriesStored(),
      summary.memoriesSuperseded(),
      summary.graphEntities(),
      summary.graphEdges());
  }

  @GetMapping("/code/status")
  public CodeStatusResponse status(@PathVariable String name) {
    return store.findProfile(name).map(Profile::id).map(profileId -> {
      CodeIndexCounts counts = codeStore.counts(profileId);

      return new CodeStatusResponse(
        codeStore.isCodeIndexPresent(profileId),
        counts.files(),
        counts.symbols(),
        counts.resolvedEdges(),
        counts.heuristicEdges(),
        counts.edges());
    }).orElse(EMPTY_STATUS);
  }
}
