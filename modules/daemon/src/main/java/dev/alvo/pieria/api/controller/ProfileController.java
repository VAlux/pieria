package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.RememberRequest;
import dev.alvo.pieria.api.response.GraphResponse;
import dev.alvo.pieria.api.response.IngestResponse;
import dev.alvo.pieria.api.response.MemoryListResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.ProfileStatsResponse;
import dev.alvo.pieria.api.response.ProfileStatsResponse.ProfileImpact;
import dev.alvo.pieria.api.response.ProfileStatsResponse.ProfileSpend;
import dev.alvo.pieria.api.response.ProfileStatsResponse.ProfileSpend.TierSpend;
import dev.alvo.pieria.api.response.RecallResponse;
import dev.alvo.pieria.api.response.RecallResponse.CodeEvidence;
import dev.alvo.pieria.api.response.RecallResponse.RecallDebug;
import dev.alvo.pieria.api.response.RecallResponse.RecallDebug.ChannelDiagnostic;
import dev.alvo.pieria.api.response.RecallResponse.RecallDebug.Provenance;
import dev.alvo.pieria.api.response.TaskSubmitResponse;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.domain.graph.GraphSnapshot;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.domain.profile.ProfileStats;
import dev.alvo.pieria.domain.profile.ProfileUsage;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.ingestion.transcript.TranscriptParserRegistry;
import dev.alvo.pieria.model.usage.InferenceTier;
import dev.alvo.pieria.model.usage.TierUsage;
import dev.alvo.pieria.retrieval.RecallResult;
import dev.alvo.pieria.retrieval.RetrievalService;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.TemporalFact;
import dev.alvo.pieria.storage.MemoryStore;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
  private final MemoryStore store;
  private final ObjectMapper objectMapper;
  private final TaskRegistry tasks;
  private final Converter<Memory, MemoryResponse> memoryResponseConverter;
  private final PieriaProperties properties;
  private final TranscriptParserRegistry transcriptParsers;

  public ProfileController(IngestionService ingestionService,
                           RetrievalService retrievalService,
                           MemoryStore store,
                           ObjectMapper objectMapper,
                           TaskRegistry tasks,
                           Converter<Memory, MemoryResponse> memoryResponseConverter,
                           PieriaProperties properties,
                           TranscriptParserRegistry transcriptParsers) {
    this.ingestionService = ingestionService;
    this.retrievalService = retrievalService;
    this.store = store;
    this.objectMapper = objectMapper;
    this.tasks = tasks;
    this.memoryResponseConverter = memoryResponseConverter;
    this.properties = properties;
    this.transcriptParsers = transcriptParsers;
  }

  @PostMapping("/ingest")
  public IngestResponse ingest(@PathVariable String name,
                               @Valid @RequestBody IngestRequest request) {
    List<Message> messages = request.messages().stream()
      .map(m -> Message.of(request.sessionId(), m.role(), m.content()))
      .toList();

    List<Memory> stored = ingestionService.ingest(name, request.sessionId(), messages);

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
      List<Memory> stored = ingestionService.ingest(name, request.sessionId(), messages, progress);
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
    boolean fast = Boolean.TRUE.equals(request.fast());
    RecallResult result = retrievalService.recall(name, request.query(), limit, debug, fast);

    List<MemoryResponse> memories = result.candidates().stream()
      .map(candidate -> this.memoryResponseConverter.convert(candidate.memory()))
      .toList();

    return new RecallResponse(result.answer(), memories, codeEvidence(result), debug ? debugBlock(result) : null);
  }

  /**
   * Content-negotiated companion to {@link #recall}: returns a compact, injection-ready text block
   * (one line per memory) instead of JSON, for the auto-recall hooks that pipe stdout straight into a
   * Claude Code session. Always runs the fast path (deterministic analysis, no synthesis) regardless
   * of the request's {@code fast} flag — there is no answer to synthesize when the caller only wants
   * the memories. Returns {@code 204 No Content} when nothing was recalled so the hook injects nothing.
   */
  @PostMapping(value = "/recall", produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> recallContext(@PathVariable String name,
                                              @Valid @RequestBody RecallRequest request) {
    int limit = request.limit() == null ? 10 : request.limit();
    RecallResult result = retrievalService.recall(name, request.query(), limit, false, true);

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
    var profile = store.findProfile(name).orElseThrow(() -> NotFoundException.profile(name));

    ProfileStats stats = store.profileStats(profile.id());
    Long backlog = store.vectorizationOutboxDepth().isPresent()
      ? store.vectorizationOutboxDepth().getAsLong()
      : null;

    return new ProfileStatsResponse(
      profile.name(),
      profile.createdAt(),
      stats.totalActive(),
      stats.byType(),
      stats.superseded(),
      stats.sessions(),
      stats.firstMemoryAt(),
      stats.lastMemoryAt(),
      backlog,
      impactOf(store.usageStats(profile.id())),
      spendOf(store.inferenceUsage(profile.id())));
  }

  /**
   * Map the stored lifetime counters to the wire impact block, stamping the display knobs.
   */
  private ProfileImpact impactOf(ProfileUsage usage) {
    // Stats binds with @DefaultValue in production; guard null for tests that construct properties directly.
    PieriaProperties.Stats cfg = properties == null ? null : properties.stats();
    int window = cfg == null ? 200_000 : cfg.contextWindowTokens();
    double price = cfg == null ? 0.0 : cfg.pricePerMillionTokens();
    return new ProfileImpact(
      usage.recallCount(),
      usage.tokensSavedEvidence(),
      usage.tokensSavedNaive(),
      usage.tokensIngested(),
      usage.tokensStored(),
      window,
      price);
  }

  /**
   * Map the stored per-tier inference-spend counters to the wire spend block, costing each tier with
   * its configured input/output prices. Returns {@code null} when nothing has been spent yet so the
   * client renders no panel.
   */
  private ProfileSpend spendOf(Map<InferenceTier, TierUsage> usage) {
    if (usage == null || usage.isEmpty()) {
      return null;
    }
    // Stats binds with @DefaultValue in production; guard null for tests that construct properties directly.
    Map<String, PieriaProperties.Stats.TierPrice> prices =
      (properties == null || properties.stats() == null) ? Map.of() : properties.stats().spend();

    List<TierSpend> tiers = new ArrayList<>();
    long totalPrompt = 0;
    long totalCompletion = 0;
    double totalCost = 0.0;
    boolean costAvailable = false;

    for (Map.Entry<InferenceTier, TierUsage> entry : usage.entrySet()) {
      String tierName = entry.getKey().name().toLowerCase(Locale.ROOT);
      TierUsage u = entry.getValue();
      PieriaProperties.Stats.TierPrice price = prices.get(tierName);

      double cost = 0.0;
      if (price != null) {
        cost = u.promptTokens() / 1_000_000.0 * price.inputPrice()
          + u.completionTokens() / 1_000_000.0 * price.outputPrice();
        if (price.inputPrice() > 0.0 || price.outputPrice() > 0.0) {
          costAvailable = true;
        }
      }

      tiers.add(new TierSpend(tierName, u.calls(), u.promptTokens(), u.completionTokens(), cost));
      totalPrompt += u.promptTokens();
      totalCompletion += u.completionTokens();
      totalCost += cost;
    }

    return new ProfileSpend(tiers, totalPrompt, totalCompletion, totalCost, costAvailable);
  }

  @GetMapping("/memories")
  public MemoryListResponse list(@PathVariable String name,
                                 @RequestParam(name = "type", required = false) String type,
                                 @RequestParam(name = "session", required = false) String session,
                                 @RequestParam(name = "includeSuperseded", defaultValue = "false") boolean includeSuperseded) {
    var profile = store.findProfile(name).orElseThrow(() -> NotFoundException.profile(name));

    MemoryType typeFilter = type == null ? null : MemoryType.fromWire(type);
    List<Memory> memories = store.listMemories(profile.id(), typeFilter, session, includeSuperseded);
    return new MemoryListResponse(memories.stream().map(this.memoryResponseConverter::convert).toList());
  }

  @DeleteMapping("/memories/{id}")
  public ResponseEntity<Void> forget(@PathVariable String name, @PathVariable String id) {
    var profile = store.findProfile(name).orElseThrow(() -> NotFoundException.profile(name));

    if (!store.forgetMemory(profile.id(), id)) {
      throw NotFoundException.memory(id);
    }
    return ResponseEntity.noContent().build();
  }

  /**
   * The profile's entity-relation graph as node/link JSON for the force-directed viewer. Only
   * entities connected by an edge off an active (non-superseded) memory are returned. Edge
   * provenance snippets are truncated for a compact payload.
   */
  @GetMapping("/graph")
  public GraphResponse graph(@PathVariable String name) {
    var profile = store.findProfile(name).orElseThrow(() -> NotFoundException.profile(name));

    GraphSnapshot snapshot = store.graphSnapshot(profile.id());

    List<GraphResponse.Node> nodes = snapshot.nodes().stream()
      .map(e -> new GraphResponse.Node(e.id(), e.type(), e.name()))
      .toList();
    List<GraphResponse.Link> links = snapshot.links().stream()
      .map(l -> new GraphResponse.Link(l.sourceEntityId(), l.targetEntityId(), l.relation(),
        l.memoryId(), oneLine(l.memoryContent(), 200)))
      .toList();

    return new GraphResponse(nodes, links);
  }

  /**
   * Convenience entry point for humans: redirect to the console's graph tab with this profile
   * pre-selected, so {@code /v1/profiles/{name}/graph/view} opens a ready-to-use page.
   */
  @GetMapping("/graph/view")
  public ResponseEntity<Void> graphView(@PathVariable String name) {
    var viewer = URI.create("/index.html?view=graph&profile=%s"
      .formatted(URLEncoder.encode(name, StandardCharsets.UTF_8)));
    return ResponseEntity.status(HttpStatus.FOUND).location(viewer).build();
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
