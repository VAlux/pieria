package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.request.CodeIndexRequest;
import dev.alvo.pieria.api.response.CodeIndexResponse;
import dev.alvo.pieria.api.response.CodeStatusResponse;
import dev.alvo.pieria.api.response.TaskSubmitResponse;
import dev.alvo.pieria.code.CodeIndexingService;
import dev.alvo.pieria.code.CodeIndexingService.CodeIndexStatus;
import dev.alvo.pieria.code.CodeIndexingService.CodeIndexSummary;
import dev.alvo.pieria.code.CodeIndexingService.SourceFile;
import dev.alvo.pieria.code.CodeSummarizationService;
import dev.alvo.pieria.code.CodeSummarizationService.SummarizationResult;
import dev.alvo.pieria.config.CodeSummarizationProperties;
import dev.alvo.pieria.task.TaskRegistry;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/**
 * index a batch of source files and read code-index status. Indexing is deterministic (no model
 * call); vectorization of derived memories continues asynchronously through the existing outbox.
 * The optional LLM narrative summarization pass runs only on the async endpoint — the synchronous
 * {@code POST /code} stays model-free by contract.
 */
@RestController
@RequestMapping("/v1/profiles/{name}")
public class CodeController {

  private static final Logger log = LoggerFactory.getLogger(CodeController.class);

  private static final CodeStatusResponse EMPTY_STATUS =
    new CodeStatusResponse(false, 0, 0, 0, 0, 0);

  private final CodeIndexingService indexing;
  private final CodeSummarizationService summarization;
  private final CodeSummarizationProperties summarizationProperties;
  private final ObjectMapper objectMapper;
  private final TaskRegistry tasks;

  public CodeController(CodeIndexingService indexing,
                        CodeSummarizationService summarization,
                        CodeSummarizationProperties summarizationProperties,
                        ObjectMapper objectMapper, TaskRegistry tasks) {
    this.indexing = indexing;
    this.summarization = summarization;
    this.summarizationProperties = summarizationProperties;
    this.objectMapper = objectMapper;
    this.tasks = tasks;
  }

  @PostMapping("/code")
  public CodeIndexResponse index(@PathVariable String name, @Valid @RequestBody CodeIndexRequest request) {
    List<SourceFile> files = toSourceFiles(request);

    CodeIndexSummary summary = indexing.index(name, request.treeHash(), files,
      request.reindex(), dev.alvo.pieria.ingestion.IngestProgressListener.noop());

    return toResponse(summary, SummarizationResult.empty());
  }

  /**
   * Async variant of {@link #index}: start indexing on a background task and return its id
   * immediately so the client can poll {@code GET /v1/tasks/{taskId}} and render progress. The
   * terminal result carries the full {@link CodeIndexResponse} summary. When enabled (request
   * {@code summarize} flag, falling back to config), the LLM narrative summarization pass runs
   * after indexing — best-effort: its failure never affects the index result.
   */
  @PostMapping("/code/async")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskSubmitResponse indexAsync(@PathVariable String name,
                                       @RequestParam(name = "label", required = false) String label,
                                       @Valid @RequestBody CodeIndexRequest request) {
    List<SourceFile> files = toSourceFiles(request);

    boolean summarize = request.summarize() != null
      ? request.summarize()
      : summarizationProperties.enabled();

    String kind = label == null || label.isBlank() ? "code" : label;
    UUID taskId = tasks.submit(kind, name, progress -> {
      CodeIndexSummary summary = indexing.index(name, request.treeHash(), files, request.reindex(), progress);
      SummarizationResult summaries = SummarizationResult.empty();
      if (summarize) {
        try {
          summaries = summarization.summarize(name, files, progress);
        } catch (RuntimeException e) {
          log.warn("code summarization failed ({}); index result unaffected", e.toString());
        }
      }
      return objectMapper.valueToTree(toResponse(summary, summaries));
    });
    return new TaskSubmitResponse(taskId.toString());
  }

  private static List<SourceFile> toSourceFiles(CodeIndexRequest request) {
    return request.files().stream()
      .map(f -> new SourceFile(f.repoRelPath(), f.language(), f.contentHash(), f.content()))
      .toList();
  }

  private static CodeIndexResponse toResponse(CodeIndexSummary summary, SummarizationResult summaries) {
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
      summary.graphEdges(),
      summaries.stored(),
      summaries.skippedUnchanged(),
      summaries.failed());
  }

  @GetMapping("/code/status")
  public CodeStatusResponse status(@PathVariable String name) {
    return indexing.status(name)
      .map(s -> new CodeStatusResponse(
        s.present(), s.files(), s.symbols(), s.resolvedEdges(), s.heuristicEdges(), s.edges()))
      .orElse(EMPTY_STATUS);
  }
}
