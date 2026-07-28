package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.RememberRequest;
import dev.alvo.pieria.api.response.ExportLineResponse;
import dev.alvo.pieria.api.response.IngestResponse;
import dev.alvo.pieria.api.response.MemoryListResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.ProfileStatsResponse;
import dev.alvo.pieria.api.response.ProfileStatsResponse.ProfileImpact;
import dev.alvo.pieria.api.response.ProfileSummary;
import dev.alvo.pieria.api.response.ProfileStatsResponse.ProfileSpend;
import dev.alvo.pieria.api.response.ProfileStatsResponse.ProfileSpend.TierSpend;
import dev.alvo.pieria.api.response.RecallResponse;
import dev.alvo.pieria.api.response.RecallResponse.CodeEvidence;
import dev.alvo.pieria.api.response.RecallResponse.RecallDebug;
import dev.alvo.pieria.api.response.RecallResponse.RecallDebug.ChannelDiagnostic;
import dev.alvo.pieria.api.response.RecallResponse.RecallDebug.Provenance;
import dev.alvo.pieria.api.response.TaskSubmitResponse;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.ingestion.transcript.TranscriptParserRegistry;
import dev.alvo.pieria.profile.ProfileService;
import dev.alvo.pieria.profile.ProfileStatsService;
import dev.alvo.pieria.profile.ProfileStatsService.ProfileStatsView;
import dev.alvo.pieria.retrieval.RecallResult;
import dev.alvo.pieria.retrieval.RetrievalService;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.TemporalFact;
import dev.alvo.pieria.task.TaskRegistry;
import jakarta.validation.Valid;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST surface for a single profile. All paths are scoped to
 * {@code /v1/profiles/{name}}; the profile name is resolved to an internal id only inside the
 * services, never leaked back to the client.
 */
@RestController
@RequestMapping("/v1/profiles/{name}")
public class ProfileController {

  private static final String NDJSON = "application/x-ndjson";

  private final IngestionService ingestionService;
  private final RetrievalService retrievalService;
  private final ProfileService profileService;
  private final ProfileStatsService profileStatsService;
  private final ObjectMapper objectMapper;
  private final TaskRegistry tasks;
  private final Converter<Memory, MemoryResponse> memoryResponseConverter;
  private final Converter<ExportRow, ExportLineResponse> exportLineConverter;
  private final TranscriptParserRegistry transcriptParsers;

  public ProfileController(IngestionService ingestionService,
                           RetrievalService retrievalService,
                           ProfileService profileService,
                           ProfileStatsService profileStatsService,
                           ObjectMapper objectMapper,
                           TaskRegistry tasks,
                           Converter<Memory, MemoryResponse> memoryResponseConverter,
                           Converter<ExportRow, ExportLineResponse> exportLineConverter,
                           TranscriptParserRegistry transcriptParsers) {
    this.ingestionService = ingestionService;
    this.retrievalService = retrievalService;
    this.profileService = profileService;
    this.profileStatsService = profileStatsService;
    this.objectMapper = objectMapper;
    this.tasks = tasks;
    this.memoryResponseConverter = memoryResponseConverter;
    this.exportLineConverter = exportLineConverter;
    this.transcriptParsers = transcriptParsers;
  }

  /**
   * Create a brand-new, empty profile named {@code name}. Idempotency is intentionally <em>not</em>
   * offered here: creating a profile that already exists is a {@code 409} conflict, so the caller
   * always learns whether it created the profile or hit an existing one.
   */
  @PutMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProfileSummary create(@PathVariable String name) {
    Profile profile = profileService.create(name);
    return new ProfileSummary(profile.name(), profile.createdAt(), 0);
  }

  /**
   * Delete a profile and every memory it owns. Hard, irreversible physical delete (not the logical
   * supersession used by {@code forget}). A {@code 404} when there is no such profile.
   */
  @DeleteMapping
  public ResponseEntity<Void> delete(@PathVariable String name) {
    profileService.delete(name);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/ingest")
  public IngestResponse ingest(@PathVariable String name,
                               @Valid @RequestBody IngestRequest request) {
    List<Message> messages = request.messages().stream()
      .map(m -> Message.of(request.sessionId(), m.role(), m.content()))
      .toList();

    List<Memory> stored =
      ingestionService.ingest(name, request.sessionId(), messages, request.extractionSamples());

    return IngestResponse.of(stored.stream().map(this.memoryResponseConverter::convert).toList());
  }

  /**
   * Ingest a raw harness session transcript. The transcript is parsed server-side into conversation
   * messages by the {@link TranscriptParserRegistry} implementation for {@code harness} (default
   * {@code claude-code}), so harness hooks stay dependency-free and only need to POST the raw file.
   * An empty/unusable transcript yields an empty response rather than an error, so a fail-closed
   * hook never sees a non-2xx for an uneventful session. An unknown {@code harness} is a 400.
   */
  @PostMapping(path = "/ingest/transcript", consumes = NDJSON)
  public IngestResponse ingestTranscript(@PathVariable String name,
                                         @RequestParam(name = "sessionId", required = false) String sessionId,
                                         @RequestParam(name = "harness", defaultValue = "claude-code") String harness,
                                         @RequestBody String transcript) {
    String resolvedSessionId = (sessionId == null || sessionId.isBlank())
      ? "session-" + UUID.randomUUID()
      : sessionId;

    List<Message> messages = transcriptParsers.forHarness(harness).parse(transcript, resolvedSessionId);
    if (messages.isEmpty()) {
      return IngestResponse.of(List.of());
    }

    List<Memory> stored = ingestionService.ingest(name, resolvedSessionId, messages);

    return IngestResponse.of(stored.stream().map(this.memoryResponseConverter::convert).toList());
  }

  /**
   * Async variant of {@link #ingest}: start extraction on a background task and return its id
   * immediately so the client can poll {@code GET /v1/tasks/{taskId}} and render progress. The
   * terminal result carries the stored-memory {@code count}.
   */
  @PostMapping("/ingest/async")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskSubmitResponse ingestAsync(@PathVariable String name,
                                        @RequestParam(name = "label", required = false) String label,
                                        @Valid @RequestBody IngestRequest request) {
    List<Message> messages = request.messages().stream()
      .map(m -> Message.of(request.sessionId(), m.role(), m.content()))
      .toList();

    String kind = label == null || label.isBlank() ? "ingest" : label;
    UUID taskId = tasks.submit(kind, name, progress -> {
      var ingest = progress.lane("ingest");
      ingest.start();
      List<Memory> stored =
        ingestionService.ingest(name, request.sessionId(), messages, request.extractionSamples(), ingest);
      ingest.complete();
      return objectMapper.valueToTree(Map.of("count", stored.size()));
    });
    return new TaskSubmitResponse(taskId.toString());
  }

  @PostMapping("/memories")
  @ResponseStatus(HttpStatus.CREATED)
  public MemoryResponse remember(@PathVariable String name,
                                 @Valid @RequestBody RememberRequest request) {
    MemoryType type = MemoryType.fromWire(request.type());
    Memory memory = Memory.of(type, request.content(), request.sessionId(),
      request.topicKey(), request.payload());
    return this.memoryResponseConverter.convert(ingestionService.remember(name, memory));
  }

  @PostMapping("/recall")
  public RecallResponse recall(@PathVariable String name,
                               @Valid @RequestBody RecallRequest request) {
    int limit = request.limit() == null ? 10 : request.limit();
    boolean debug = Boolean.TRUE.equals(request.debug());
    RecallResult result = retrievalService.recall(name, request.query(), limit, debug, request.mode());

    List<MemoryResponse> memories = result.candidates().stream()
      .map(candidate -> this.memoryResponseConverter.convert(candidate.memory()))
      .toList();

    return new RecallResponse(result.answer(), memories, codeEvidence(result), debug ? debugBlock(result) : null);
  }

  /**
   * Content-negotiated companion to {@link #recall}: returns a compact, injection-ready text block
   * (one line per memory) instead of JSON, for the auto-recall hooks that pipe stdout straight into a
   * Claude Code session. Always runs the {@link RecallMode#EVIDENCE} tier (deterministic analysis, no
   * synthesis) regardless of the request's {@code mode}/{@code fast} — there is no answer to synthesize
   * when the caller only wants the memories — and excludes code-indexer memories from the injected
   * block. Returns {@code 204 No Content} when nothing was recalled so the hook injects nothing.
   */
  @PostMapping(value = "/recall", produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> recallContext(@PathVariable String name,
                                              @Valid @RequestBody RecallRequest request) {
    int limit = request.limit() == null ? 10 : request.limit();
    RecallResult result = retrievalService.recall(name, request.query(), limit, false, RecallMode.EVIDENCE, true);

    String block = renderContextBlock(name, result.candidates());
    return block.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(block);
  }

  /**
   * Render fused recall candidates as a compact, injection-ready text block; empty string when none.
   */
  private static String renderContextBlock(String profile, List<RecallCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return "";
    }
    StringBuilder block = new StringBuilder()
      .append("[pieria] Relevant prior context for profile \"").append(profile).append("\"\n")
      .append("(recalled memories — verify against current code before relying on them):\n");
    for (RecallCandidate candidate : candidates) {
      Memory memory = candidate.memory();
      block.append("- (").append(memory.type().wire()).append(") ")
        .append(oneLine(memory.content(), 200)).append('\n');
    }
    return block.toString();
  }

  /**
   * Collapse whitespace to single spaces and truncate to {@code max} chars with an ellipsis.
   */
  private static String oneLine(String text, int max) {
    if (text == null) {
      return "";
    }
    String collapsed = text.strip().replaceAll("\\s+", " ");
    return collapsed.length() <= max ? collapsed : collapsed.substring(0, max - 1) + "…";
  }

  /**
   * The code-graph evidence lines as DTOs, or {@code null} (omitted from JSON) when there are none.
   */
  private static List<CodeEvidence> codeEvidence(RecallResult result) {
    if (result.graphEvidence().isEmpty()) {
      return null;
    }
    return result.graphEvidence().stream()
      .map(e -> new CodeEvidence(e.src(), e.srcPath(), e.relation(), e.dst(), e.dstPath(), e.confidence()))
      .toList();
  }

  private static RecallDebug debugBlock(RecallResult result) {
    List<Provenance> candidates = result.candidates().stream()
      .map(candidate -> new Provenance(candidate.memory().id(), candidate.score(), candidate.source()))
      .toList();

    List<String> temporalFacts = result.temporalFacts().stream()
      .map(TemporalFact::render)
      .toList();

    List<ChannelDiagnostic> channels = result.diagnostics() == null ? List.of() : result.diagnostics().channels().stream()
      .map(d -> new ChannelDiagnostic(d.channel().name().toLowerCase(java.util.Locale.ROOT), d.latencyMs(), d.hits(), d.failed()))
      .toList();

    return new RecallDebug(candidates, temporalFacts, channels);
  }

  @GetMapping("/stats")
  public ProfileStatsResponse stats(@PathVariable String name) {
    ProfileStatsView s = profileStatsService.stats(name);

    ProfileImpact impact = new ProfileImpact(
      s.impact().recallCount(),
      s.impact().tokensSaved(),
      s.impact().tokensIngested(),
      s.impact().tokensStored(),
      s.impact().contextWindowTokens(),
      s.impact().pricePerMillionTokens());

    ProfileSpend spend = s.spend() == null ? null : new ProfileSpend(
      s.spend().tiers().stream()
        .map(t -> new TierSpend(t.tier(), t.calls(), t.promptTokens(), t.completionTokens(), t.cost()))
        .toList(),
      s.spend().totalPrompt(),
      s.spend().totalCompletion(),
      s.spend().totalCost(),
      s.spend().costAvailable());

    return new ProfileStatsResponse(
      s.name(), s.createdAt(), s.totalActive(), s.byType(), s.superseded(), s.sessions(),
      s.firstMemoryAt(), s.lastMemoryAt(), s.backlog(), impact, spend);
  }

  @GetMapping("/memories")
  public MemoryListResponse list(@PathVariable String name,
                                 @RequestParam(name = "type", required = false) String type,
                                 @RequestParam(name = "session", required = false) String session,
                                 @RequestParam(name = "includeSuperseded", defaultValue = "false") boolean includeSuperseded) {
    MemoryType typeFilter = type == null ? null : MemoryType.fromWire(type);
    List<Memory> memories = profileService.list(name, typeFilter, session, includeSuperseded);
    return new MemoryListResponse(memories.stream().map(this.memoryResponseConverter::convert).toList());
  }

  @DeleteMapping("/memories/{id}")
  public ResponseEntity<Void> forget(@PathVariable String name, @PathVariable String id) {
    profileService.forget(name, id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping(value = "/export", produces = NDJSON)
  public ResponseEntity<String> export(@PathVariable String name) {
    List<ExportRow> rows = profileService.export(name);
    StringBuilder body = new StringBuilder();

    rows.forEach(row -> body.append(writeLine(exportLineConverter.convert(row))).append('\n'));

    return ResponseEntity.ok()
      .contentType(MediaType.parseMediaType(NDJSON))
      .body(body.toString());
  }

  private String writeLine(ExportLineResponse line) {
    try {
      return objectMapper.writeValueAsString(line);
    } catch (tools.jackson.core.JacksonException e) {
      throw new IllegalStateException("failed to serialize export row", e);
    }
  }
}
