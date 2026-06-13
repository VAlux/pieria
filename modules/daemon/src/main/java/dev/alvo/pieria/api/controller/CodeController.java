package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.request.CodeIndexRequest;
import dev.alvo.pieria.api.response.CodeIndexResponse;
import dev.alvo.pieria.api.response.CodeStatusResponse;
import dev.alvo.pieria.code.CodeIndexingService;
import dev.alvo.pieria.code.CodeIndexingService.CodeIndexSummary;
import dev.alvo.pieria.code.CodeIndexingService.SourceFile;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.storage.CodeIndexStore;
import dev.alvo.pieria.storage.CodeIndexStore.CodeIndexCounts;
import dev.alvo.pieria.storage.MemoryStore;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

  public CodeController(CodeIndexingService indexing, CodeIndexStore codeStore, MemoryStore store) {
    this.indexing = indexing;
    this.codeStore = codeStore;
    this.store = store;
  }

  @PostMapping("/code")
  public CodeIndexResponse index(@PathVariable String name, @Valid @RequestBody CodeIndexRequest request) {
    List<SourceFile> files = request.files().stream()
      .map(f -> new SourceFile(f.repoRelPath(), f.language(), f.contentHash(), f.content()))
      .toList();

    CodeIndexSummary summary = indexing.index(name, request.treeHash(), files);

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
