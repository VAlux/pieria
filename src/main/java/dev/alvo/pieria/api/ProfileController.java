package dev.alvo.pieria.api;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.RememberRequest;
import dev.alvo.pieria.api.response.IngestResponse;
import dev.alvo.pieria.api.response.MemoryListResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.RecallResponse;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.NotFoundException;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.retrieval.RecallResult;
import dev.alvo.pieria.retrieval.RetrievalService;
import dev.alvo.pieria.storage.MemoryStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/**
 * REST surface for a single profile (CLAUDE.md table, SPEC 9). All paths are scoped to
 * {@code /v1/profiles/{name}}; the profile name is resolved to an internal id only inside the
 * services, never leaked back to the client.
 */
@RestController
@RequestMapping("/v1/profiles/{name}")
public class ProfileController {

  private static final String NDJSON = "application/x-ndjson";

  private final IngestionService ingestionService;
  private final RetrievalService retrievalService;
  private final MemoryStore store;
  private final ObjectMapper objectMapper;

  public ProfileController(IngestionService ingestionService,
                           RetrievalService retrievalService,
                           MemoryStore store,
                           ObjectMapper objectMapper) {
    this.ingestionService = ingestionService;
    this.retrievalService = retrievalService;
    this.store = store;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/ingest")
  public IngestResponse ingest(@PathVariable String name,
                               @Valid @RequestBody IngestRequest request) {
    List<Message> messages = request.messages().stream()
      .map(m -> Message.of(request.sessionId(), m.role(), m.content()))
      .toList();

    List<Memory> stored = ingestionService.ingest(name, request.sessionId(), messages);

    return IngestResponse.of(stored.stream().map(MemoryResponse::from).toList());
  }

  @PostMapping("/memories")
  @ResponseStatus(HttpStatus.CREATED)
  public MemoryResponse remember(@PathVariable String name,
                                 @Valid @RequestBody RememberRequest request) {
    MemoryType type = MemoryType.fromWire(request.type());
    Memory memory = Memory.of(type, request.content(), request.sessionId(),
      request.topicKey(), request.payload());
    return MemoryResponse.from(ingestionService.remember(name, memory));
  }

  @PostMapping("/recall")
  public RecallResponse recall(@PathVariable String name,
                               @Valid @RequestBody RecallRequest request) {
    int limit = request.limit() == null ? 10 : request.limit();
    RecallResult result = retrievalService.recall(name, request.query(), limit);
    return new RecallResponse(result.answer(),
      result.memories().stream().map(MemoryResponse::from).toList());
  }

  @GetMapping("/memories")
  public MemoryListResponse list(@PathVariable String name,
                                 @RequestParam(name = "type", required = false) String type,
                                 @RequestParam(name = "session", required = false) String session) {
    var profile = store.findProfile(name).orElseThrow(() -> NotFoundException.profile(name));

    MemoryType typeFilter = type == null ? null : MemoryType.fromWire(type);
    List<Memory> memories = store.listMemories(profile.id(), typeFilter, session);
    return new MemoryListResponse(memories.stream().map(MemoryResponse::from).toList());
  }

  @DeleteMapping("/memories/{id}")
  public ResponseEntity<Void> forget(@PathVariable String name, @PathVariable String id) {
    var profile = store.findProfile(name).orElseThrow(() -> NotFoundException.profile(name));

    if (!store.forgetMemory(profile.id(), id)) {
      throw NotFoundException.memory(id);
    }
    return ResponseEntity.noContent().build();
  }

  @GetMapping(value = "/export", produces = NDJSON)
  public ResponseEntity<String> export(@PathVariable String name) {
    var profile = store.findProfile(name).orElseThrow(() -> NotFoundException.profile(name));

    List<ExportRow> rows = store.exportProfile(profile.id());
    StringBuilder body = new StringBuilder();

    rows.forEach(row -> body.append(writeLine(row)).append('\n'));

    return ResponseEntity.ok()
      .contentType(MediaType.parseMediaType(NDJSON))
      .body(body.toString());
  }

  private String writeLine(ExportRow row) {
    try {
      // Build a plain map with the timestamp pre-stringified so NDJSON export does not
      // depend on a jsr310 module being registered on the injected ObjectMapper.
      Memory m = row.memory();
      var memory = new java.util.LinkedHashMap<String, Object>();
      memory.put("id", m.id());
      memory.put("type", m.type() == null ? null : m.type().wire());
      memory.put("content", m.content());
      memory.put("topicKey", m.topicKey());
      memory.put("sessionId", m.sessionId());
      memory.put("superseded", m.superseded());
      memory.put("payload", m.payload());
      memory.put("createdAt", m.createdAt() == null ? null : m.createdAt().toString());

      var line = new java.util.LinkedHashMap<String, Object>();
      line.put("profileName", row.profileName());
      line.put("memory", memory);
      return objectMapper.writeValueAsString(line);
    } catch (tools.jackson.core.JacksonException e) {
      throw new IllegalStateException("failed to serialize export row", e);
    }
  }
}
