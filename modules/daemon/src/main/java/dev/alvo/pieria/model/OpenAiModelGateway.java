package dev.alvo.pieria.model;


import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.graph.EntityNormalizer;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.ingestion.model.Chunk;
import dev.alvo.pieria.ingestion.model.Classification;
import dev.alvo.pieria.ingestion.model.UnifiedCandidate;
import dev.alvo.pieria.ingestion.model.VerificationResult;
import dev.alvo.pieria.ingestion.model.VerificationVerdict;
import dev.alvo.pieria.model.provider.ModelProviderAdapter;
import dev.alvo.pieria.model.usage.InferenceTier;
import dev.alvo.pieria.model.usage.InferenceUsageSink;
import dev.alvo.pieria.retrieval.model.GraphEvidence;
import dev.alvo.pieria.retrieval.model.QueryAnalysis;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.TemporalFact;
import dev.alvo.pieria.tools.PromptTemplateLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Spring AI implementation of {@link ModelGateway} against any OpenAI-compatible provider
 * (Ollama, LM Studio, llama.cpp, vLLM, OpenRouter, OpenAI, …) configured via {@code pieria.provider.*}.
 * Uses the small chat client for extraction (structured output) and the large chat client for
 * synthesis. Any failure reaching the provider is wrapped in {@link ModelUnavailableException} with a
 * generic message so provider hosts/secrets never leak to callers.
 */
@Component
public class OpenAiModelGateway implements ModelGateway {

  private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiModelGateway.class);

  /**
   * Per-memory graph caps used when no ingestion config is bound (tests, minimal setups).
   */
  private static final int DEFAULT_MAX_GRAPH_ITEMS = 3;
  /**
   * Longest batch-graph reply still read as an honest "nothing to extract" when it contains no
   * parseable line. Anything longer is malformed and gets retried instead.
   */
  private static final int EMPTY_GRAPH_REPLY_MAX_CHARS = 24;
  /**
   * Batch-graph attempts at getting a parseable reply before dropping to per-memory calls.
   */
  private static final int BATCH_GRAPH_FORMAT_ATTEMPTS = 2;
  private static final ObjectMapper objectMapper = new ObjectMapper();
  /**
   * Parses unified-extraction output tolerating two shapes: the expected bare array {@code [...]}
   * and a wrapped object {@code {"candidates":[...]}}. Strips markdown code fences that some models
   * emit around JSON. When neither parses, salvages content lines from markdown-style output and
   * enriches them through {@link #classifyAll} so a JSON-shy model still yields usable candidates.
   */
  private static final Pattern MD_CONTENT = Pattern.compile(
    "(?im)^[-*\\d.]*\\s*\\*{0,2}(?:content)\\*{0,2}:?\\s*(.+)$");
  private final ChatClient extractionChatClient;
  private final ChatClient synthesisChatClient;
  private final EmbeddingModel embeddingModel;
  private final PieriaProperties properties;
  private final ModelProviderAdapter providerAdapter;
  private final ModelCallRetry retry;
  private final StructuredCallLimiter structuredCalls;

  public OpenAiModelGateway(@Qualifier("extractionChatClient") ChatClient extractionChatClient,
                            @Qualifier("synthesisChatClient") ChatClient synthesisChatClient,
                            EmbeddingModel embeddingModel,
                            PieriaProperties properties,
                            ModelProviderAdapter providerAdapter) {
    this.extractionChatClient = extractionChatClient;
    this.synthesisChatClient = synthesisChatClient;
    this.embeddingModel = embeddingModel;
    this.properties = properties;
    this.providerAdapter = providerAdapter;
    this.retry = new ModelCallRetry(properties.model().retry());
    this.structuredCalls = new StructuredCallLimiter(
      properties.model().maxConcurrentStructuredCalls());
  }

  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.strip();
  }

  /**
   * The classifier prompt asks the model to hand-write {@code payload} as a JSON object *string*, so
   * Jackson binds it opaquely and never validates it. Anything but a JSON object is dropped to
   * {@code "{}"}: the column feeds SQLite's {@code json_each}, which aborts the whole statement on
   * malformed JSON, so one bad row would otherwise take down retrieval for the entire profile.
   */
  private static String sanitizePayload(String payloadRaw) {
    String payload = blankToNull(payloadRaw);
    if (payload == null) {
      return "{}";
    }
    try {
      if (objectMapper.readTree(payload).isObject()) {
        return payload;
      }
    } catch (RuntimeException e) {
      // fall through to the empty payload below
    }
    LOGGER.warn("dropping non-object model payload ({} chars)", payload.length());
    return "{}";
  }

  /**
   * Log prompt/completion/total token usage for a single model-call {@code stage}. Null-safe at
   * every level: a missing {@link ChatResponse}, metadata, or {@link Usage} simply logs zeros.
   */
  private static void logTokenUsage(String stage, ChatResponse chatResponse) {
    int prompt = 0;
    int completion = 0;
    int total = 0;
    if (chatResponse != null) {
      Usage usage = chatResponse.getMetadata().getUsage();
      prompt = nullToZero(usage.getPromptTokens());
      completion = nullToZero(usage.getCompletionTokens());
      total = nullToZero(usage.getTotalTokens());
    }
    LOGGER.info("model stage={} promptTokens={} completionTokens={} totalTokens={}",
      stage, prompt, completion, total);
    InferenceUsageSink.current().add(InferenceTier.forStage(stage), prompt, completion, 1);
  }

  private static int nullToZero(Integer value) {
    return value == null ? 0 : value;
  }

  private static List<String> scrapeMarkdownContents(String text) {
    List<String> contents = new ArrayList<>();
    for (String line : text.split("\n")) {
      Matcher cm = MD_CONTENT.matcher(line);
      if (cm.find()) {
        String content = cm.group(1).strip().replaceAll("\\*+", "").strip();
        if (!content.isBlank()) {
          contents.add(content);
        }
      }
    }
    return contents;
  }

  private static String stripCodeFences(String text) {
    String s = text.strip();
    if (s.startsWith("```")) {
      int newline = s.indexOf('\n');
      if (newline >= 0) {
        s = s.substring(newline + 1);
      }
      if (s.endsWith("```")) {
        s = s.substring(0, s.length() - 3).stripTrailing();
      }
    }
    return s;
  }

  // --- Pipeline stages: extraction, verification, classification ----
  // All stages below run on the small/fast extraction client for structured output.
  // The prompts (resources under prompts/) and the structured-output record shapes below are part
  // of the stable contract the ingestion + test layers rely on; bump a version note here if the
  // JSON shapes change. Prompt/shape version: v1 (unified extraction — one call emits candidates
  // with their classification; there is no separate full/detail/classify cascade).

  private static VerificationResult toVerificationResult(VerificationItemDto item, String content) {
    if (item == null || item.verdict() == null) {
      return new VerificationResult(VerificationVerdict.DROP, "", "no verdict in batch result");
    }
    VerificationVerdict verdict;
    try {
      verdict = VerificationVerdict.fromWire(item.verdict());
    } catch (IllegalArgumentException e) {
      return new VerificationResult(VerificationVerdict.DROP, "", "unparseable verdict");
    }
    String resolved = switch (verdict) {
      case PASS -> content;
      case CORRECT -> blankToNull(item.content()) == null ? content : item.content().strip();
      case DROP -> "";
    };
    return new VerificationResult(verdict, resolved, blankToNull(item.reason()));
  }

  private static <T> List<T> limit(List<T> values, int cap) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return values.size() <= cap ? values : values.subList(0, cap);
  }

  /**
   * Give each {@code (source, relation, target)} its endpoint types from the same memory's declared
   * entities, matched on the normalized name so lookup agrees with how {@link #toGraphFragment}
   * dedupes. An endpoint the model never declared falls through with a null type, which normalizes
   * to {@code concept}.
   */
  private static List<TripleDto> resolveTriples(List<String[]> triples, List<EntityDto> entities) {
    if (triples.isEmpty()) {
      return List.of();
    }
    Map<String, String> typeByName = new HashMap<>();
    for (EntityDto entity : entities) {
      typeByName.putIfAbsent(EntityNormalizer.normalizeName(entity.name()), entity.type());
    }
    List<TripleDto> resolved = new ArrayList<>(triples.size());
    for (String[] triple : triples) {
      resolved.add(new TripleDto(
        triple[0], typeByName.get(EntityNormalizer.normalizeName(triple[0])),
        triple[1],
        triple[2], typeByName.get(EntityNormalizer.normalizeName(triple[2]))));
    }
    return resolved;
  }

  /**
   * Parse one {@code type:name} entity token; {@code null} when there is no usable name.
   */
  private static EntityDto parseEntityToken(String token) {
    String trimmed = token == null ? "" : token.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    int colon = trimmed.indexOf(':');
    String type = colon < 0 ? null : trimmed.substring(0, colon).strip();
    String name = colon < 0 ? trimmed : trimmed.substring(colon + 1).strip();
    return name.isEmpty() ? null : new EntityDto(name, type);
  }

  private static Integer parseIndex(String raw) {
    String trimmed = raw == null ? "" : raw.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    try {
      return Integer.valueOf(trimmed);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static List<GraphFragment> emptyFragments(int count) {
    List<GraphFragment> results = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      results.add(GraphFragment.empty());
    }
    return results;
  }

  private static MemoryType parseTypeOrNull(String wire) {
    if (wire == null || wire.isBlank()) {
      return null;
    }
    try {
      return MemoryType.fromWire(wire);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Normalize a model-supplied topic key into a stable lowercase dot-joined key, or {@code null}.
   */
  static String normalizeTopicKey(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalized = raw.strip().toLowerCase(java.util.Locale.ROOT)
      .replaceAll("[^a-z0-9]+", ".")
      .replaceAll("^\\.+|\\.+$", "");
    return normalized.isBlank() ? null : normalized;
  }

  private static String joinedOrNone(List<String> lines) {
    return lines == null || lines.isEmpty()
      ? "(none)"
      : lines.stream().map(l -> "- " + l).collect(Collectors.joining("\n"));
  }

  /**
   * Maps the judge's one-word reply to a verdict. An unparseable reply is not silently scored as a
   * failure — it falls back to the deterministic comparison so a flaky judge cannot manufacture
   * hallucinations that never happened.
   */
  private static AnswerVerdict parseVerdict(String response, String expectedAnswer, String actualAnswer) {
    String reply = response == null ? "" : response.strip().toLowerCase(Locale.ROOT);
    if (reply.startsWith("correct")) {
      return AnswerVerdict.CORRECT;
    }
    if (reply.startsWith("abstain")) {
      return AnswerVerdict.ABSTAINED;
    }
    if (reply.startsWith("wrong")) {
      return AnswerVerdict.WRONG;
    }
    LOGGER.warn("unparseable judge verdict '{}'; falling back to exact match", truncate(response, 40));
    if (actualAnswer == null || actualAnswer.isBlank()) {
      return AnswerVerdict.ABSTAINED;
    }
    return expectedAnswer != null && expectedAnswer.strip().equalsIgnoreCase(actualAnswer.strip())
      ? AnswerVerdict.CORRECT
      : AnswerVerdict.WRONG;
  }

  private static String truncate(String s, int max) {
    if (s == null) return "(null)";
    String stripped = s.strip().replace('\n', ' ');
    return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
  }

  /**
   * Log embedding token usage when the provider reports it; skip silently otherwise. Null-safe at
   * every level. Some providers (Ollama included) do not surface embedding usage over the
   * OpenAI-compatible API, so a zero/absent usage is expected and simply skipped.
   */
  private static void logEmbeddingUsage(EmbeddingResponse response) {
    if (response == null) {
      return;
    }
    EmbeddingResponseMetadata metadata = response.getMetadata();
    Usage usage = metadata.getUsage();
    int prompt = nullToZero(usage.getPromptTokens());
    int completion = nullToZero(usage.getCompletionTokens());
    LOGGER.info("model stage=embed promptTokens={} completionTokens={} totalTokens={}",
      prompt, completion, nullToZero(usage.getTotalTokens()));
    InferenceUsageSink.current().add(InferenceTier.EMBEDDING, prompt, completion, 1);
  }

  /**
   * Run a structured-output chat call on the extraction client, log the per-stage token usage at
   * INFO, and return the bound entity. Usage capture is best-effort and never NPEs: if the provider
   * does not report usage, token counts are logged as zero. {@code stage} names the pipeline stage
   * (verify, classify, extractGraph, analyzeQuery) for the log line.
   */
  private <T> T callExtractionEntity(String prompt, String stage, Class<T> type) {
    LOGGER.debug("model call start stage={} model={} promptChars={}",
      stage, properties.model().extractionModel(), prompt.length());
    long start = System.nanoTime();
    var response = retry.execute(stage, () -> structuredCalls.execute(() -> extractionChatClient.prompt()
      .user(prompt)
      .options(reasoningOptions(stage, properties.model().extractionModel()))
      .call()
      .responseEntity(type)));
    LOGGER.debug("model call done stage={} model={} ms={}",
      stage, properties.model().extractionModel(), (System.nanoTime() - start) / 1_000_000L);

    logTokenUsage(stage, response.getResponse());
    return response.getEntity();
  }

  /**
   * As {@link #callExtractionEntity} but returning the raw assistant text, for stages whose reply is
   * a compact line protocol rather than structured JSON (see {@link #extractGraphAll}). Same retry,
   * admission gate, and usage logging; an empty/absent result yields {@code ""}.
   */
  private String callExtractionText(String prompt, String stage) {
    LOGGER.debug("model call start stage={} model={} promptChars={}",
      stage, properties.model().extractionModel(), prompt.length());
    long start = System.nanoTime();
    ChatResponse chatResponse = retry.execute(stage, () -> structuredCalls.execute(() ->
      extractionChatClient.prompt()
        .user(prompt)
        .options(reasoningOptions(stage, properties.model().extractionModel()))
        .call()
        .chatResponse()));
    LOGGER.debug("model call done stage={} model={} ms={}",
      stage, properties.model().extractionModel(), (System.nanoTime() - start) / 1_000_000L);

    logTokenUsage(stage, chatResponse);
    if (chatResponse == null || chatResponse.getResult() == null) {
      return "";
    }
    String text = chatResponse.getResult().getOutput().getText();
    return text == null ? "" : text;
  }

  /**
   * Build the chat options for {@code stage} on {@code modelName}, delegating to the configured
   * {@link ModelProviderAdapter} so whether/how {@code reasoning_effort} (see
   * {@link PieriaProperties.Model.Reasoning}) is applied stays dialect-specific — e.g. Ollama sends it
   * as configured, while Azure omits it entirely since non-reasoning deployments reject the argument.
   */
  private OpenAiChatOptions.Builder reasoningOptions(String stage, String modelName) {
    return providerAdapter.chatOptions(stage, modelName, properties.model().reasoning());
  }

  @Override
  public List<UnifiedCandidate> extractUnified(Chunk chunk) {
    if (chunk == null || chunk.transcript() == null || chunk.transcript().isBlank()) {
      return List.of();
    }
    String stage = "extract";
    String prompt = PromptTemplateLoader.render("extract-unified", Map.of(
      "transcript", chunk.transcript(),
      "queryCountInstruction", queryCountInstruction(),
      "candidateLimitInstruction", candidateLimitInstruction(),
      "graphInstruction", graphInstruction()));
    LOGGER.debug("model call start stage={} chunk={} model={} promptChars={}",
      stage, chunk.index(), properties.model().extractionModel(), prompt.length());
    long start = System.nanoTime();
    ChatResponse chatResponse;
    try {
      chatResponse = retry.execute(stage, () -> structuredCalls.execute(() -> extractionChatClient.prompt()
        .user(prompt)
        .options(reasoningOptions(stage, properties.model().extractionModel()))
        .call()
        .chatResponse()));
    } catch (RuntimeException e) {
      throw new ModelUnavailableException("model " + stage + " failed", e);
    }
    LOGGER.debug("model call done stage={} chunk={} model={} ms={}",
      stage, chunk.index(), properties.model().extractionModel(), (System.nanoTime() - start) / 1_000_000L);
    logTokenUsage(stage, chatResponse);

    String rawText = chatResponse != null && chatResponse.getResult() != null
      && chatResponse.getResult().getOutput() != null
      ? chatResponse.getResult().getOutput().getText() : null;

    List<UnifiedCandidate> candidates = new ArrayList<>();
    for (UnifiedCandidateDto raw : parseUnifiedResilient(rawText, chunk.index())) {
      if (raw == null || raw.content() == null || raw.content().isBlank()) {
        continue;
      }
      Classification classification =
        buildClassification(raw.type(), raw.topicKey(), raw.interrogativeQueries(), raw.payload());
      GraphFragment graph = graphFromExtraction()
        ? toGraphFragment(limit(raw.graphEntities(), 5), limit(raw.graphTriples(), 5))
        : GraphFragment.empty();
      candidates.add(new UnifiedCandidate(raw.content().strip(), classification, chunk.index(), stage, graph));
      int cap = maxExtractedCandidatesPerChunk();
      if (cap > 0 && candidates.size() >= cap) {
        break;
      }
    }
    return candidates;
  }

  private List<UnifiedCandidateDto> parseUnifiedResilient(String rawText, int chunkIndex) {
    if (rawText == null || rawText.isBlank()) {
      return List.of();
    }
    String json = stripCodeFences(rawText);

    // Try wrapped object {"candidates": [...]}
    try {
      UnifiedCandidateList wrapped = objectMapper.readValue(json, UnifiedCandidateList.class);
      if (wrapped != null && wrapped.candidates() != null) {
        return wrapped.candidates();
      }
    } catch (Exception ignored) {
    }

    // Try bare array [...]
    try {
      return objectMapper.readValue(json, new TypeReference<List<UnifiedCandidateDto>>() {
      });
    } catch (Exception ignored) {
    }

    // Salvage: scrape content lines from markdown-style output, then classify them in one batched
    // call so the salvaged candidates carry the same enrichment as a clean parse. The classify is
    // itself best-effort — salvage must never lose the contents it already recovered.
    List<String> salvaged = scrapeMarkdownContents(rawText);
    if (!salvaged.isEmpty()) {
      LOGGER.warn("stage=extract chunk={} output was not JSON; salvaged {} candidates via markdown scrape + classify",
        chunkIndex, salvaged.size());
      List<Classification> classifications;
      try {
        classifications = classifyAll(salvaged);
      } catch (RuntimeException e) {
        LOGGER.warn("salvage classify failed ({}); storing salvaged candidates as plain facts", e.toString());
        classifications = List.of();
      }
      List<UnifiedCandidateDto> recovered = new ArrayList<>(salvaged.size());
      for (int i = 0; i < salvaged.size(); i++) {
        Classification c = i < classifications.size()
          ? classifications.get(i)
          : buildClassification(null, null, null, null);
        recovered.add(new UnifiedCandidateDto(salvaged.get(i), c.type().wire(), c.topicKey(),
          c.interrogativeQueries(), c.payload(), null, null));
      }
      return recovered;
    }

    LOGGER.warn("stage=extract chunk={} model returned unparseable candidates output; treating as empty. Raw response: {}",
      chunkIndex, rawText);
    return List.of();
  }

  @Override
  public VerificationResult verify(String content, String transcript) {
    if (content == null || content.isBlank()) {
      return new VerificationResult(VerificationVerdict.DROP, "", "empty candidate");
    }
    String safeTranscript = transcript == null ? "" : transcript;
    String prompt = PromptTemplateLoader.render("verify-single",
      Map.of("candidate", content, "transcript", safeTranscript));

    VerificationDto dto;
    try {
      dto = callExtractionEntity(prompt, "verify", VerificationDto.class);
    } catch (RuntimeException e) {
      throw new ModelUnavailableException("model verification failed", e);
    }
    if (dto == null || dto.verdict() == null) {
      return new VerificationResult(VerificationVerdict.DROP, "", "no verdict returned");
    }
    VerificationVerdict verdict;
    try {
      verdict = VerificationVerdict.fromWire(dto.verdict());
    } catch (IllegalArgumentException e) {
      return new VerificationResult(VerificationVerdict.DROP, "", "unparseable verdict");
    }
    String resolved = switch (verdict) {
      case PASS -> content;
      case CORRECT -> blankToNull(dto.content()) == null ? content : dto.content().strip();
      case DROP -> "";
    };
    return new VerificationResult(verdict, resolved, blankToNull(dto.reason()));
  }

  @Override
  public List<VerificationResult> verifyAll(List<String> contents, String transcript) {
    if (contents == null || contents.isEmpty()) {
      return List.of();
    }
    if (contents.size() == 1) {
      return List.of(verify(contents.getFirst(), transcript));
    }
    String safeTranscript = transcript == null ? "" : transcript;
    StringBuilder numbered = new StringBuilder();
    for (int i = 0; i < contents.size(); i++) {
      numbered.append(i + 1).append(". ").append(contents.get(i)).append('\n');
    }
    String prompt = PromptTemplateLoader.render("verify-batch",
      Map.of("transcript", safeTranscript, "candidates", numbered.toString()));

    BatchVerificationDto dto;
    try {
      dto = callExtractionEntity(prompt, "verify", BatchVerificationDto.class);
    } catch (RuntimeException e) {
      // A malformed batch response (e.g. the model emitting invalid JSON) must not abort the whole
      // chunk — fall back to per-candidate verification, isolating any bad item.
      LOGGER.warn("stage=verify batch call failed ({}); falling back to per-candidate verify", e.toString());
      return verifyEachTolerant(contents, safeTranscript);
    }

    List<VerificationResult> mapped = mapBatchVerdicts(dto, contents);
    if (mapped != null) {
      return mapped;
    }
    // Batch result unusable (null/empty/no indices): fall back to per-candidate verify so a single
    // malformed batch response never silently drops a whole chunk's candidates.
    LOGGER.warn("stage=verify batch result unusable for {} candidates; falling back to per-candidate verify",
      contents.size());
    return verifyEachTolerant(contents, safeTranscript);
  }

  /**
   * Per-candidate verification that never throws: an individual call failure drops that one candidate
   * rather than aborting the chunk. (A full provider outage is already caught loudly at the extract
   * stage; by here the provider was reachable, so failures are transient/parse issues to isolate.)
   */
  private List<VerificationResult> verifyEachTolerant(List<String> contents, String transcript) {
    List<VerificationResult> results = new ArrayList<>(contents.size());
    for (String content : contents) {
      try {
        results.add(verify(content, transcript));
      } catch (RuntimeException e) {
        LOGGER.warn("verify failed for one candidate ({}); dropping it", e.toString());
        results.add(new VerificationResult(VerificationVerdict.DROP, "", "verification error"));
      }
    }
    return results;
  }

  /**
   * Map a batched verify response onto {@code contents} by 1-based index. Returns {@code null} when
   * the response is structurally unusable (so the caller falls back to per-candidate verify); a
   * candidate with no matching item becomes a DROP.
   */
  private List<VerificationResult> mapBatchVerdicts(BatchVerificationDto dto, List<String> contents) {
    if (dto == null || dto.verdicts() == null || dto.verdicts().isEmpty()) {
      return null;
    }
    Map<Integer, VerificationItemDto> byIndex = new HashMap<>();
    for (VerificationItemDto item : dto.verdicts()) {
      if (item != null && item.index() != null) {
        byIndex.putIfAbsent(item.index(), item);
      }
    }
    if (byIndex.isEmpty()) {
      return null;
    }
    List<VerificationResult> results = new ArrayList<>(contents.size());
    for (int i = 0; i < contents.size(); i++) {
      results.add(toVerificationResult(byIndex.get(i + 1), contents.get(i)));
    }
    return results;
  }

  @Override
  public Classification classify(String content) {
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be blank");
    }
    String prompt = PromptTemplateLoader.render("classify-single", Map.of(
      "content", content, "queryCountInstruction", queryCountInstruction()));

    ClassificationDto dto;
    try {
      dto = callExtractionEntity(prompt, "classify", ClassificationDto.class);
    } catch (RuntimeException e) {
      throw new ModelUnavailableException("model classification failed", e);
    }
    return dto == null
      ? buildClassification(null, null, null, null)
      : buildClassification(dto.type(), dto.topicKey(), dto.interrogativeQueries(), dto.payload());
  }

  @Override
  public List<Classification> classifyAll(List<String> contents) {
    if (contents == null || contents.isEmpty()) {
      return List.of();
    }
    if (contents.size() == 1) {
      return List.of(classify(contents.getFirst()));
    }
    StringBuilder numbered = new StringBuilder();
    for (int i = 0; i < contents.size(); i++) {
      numbered.append(i + 1).append(". ").append(contents.get(i)).append('\n');
    }
    String prompt = PromptTemplateLoader.render("classify-batch", Map.of(
      "memories", numbered.toString(), "queryCountInstruction", queryCountInstruction()));

    BatchClassificationDto dto;
    try {
      dto = callExtractionEntity(prompt, "classify", BatchClassificationDto.class);
    } catch (RuntimeException e) {
      // A malformed batch response (e.g. the model emitting invalid JSON) must not abort the chunk's
      // ingest — fall back to per-memory classification, isolating any bad item.
      LOGGER.warn("stage=classify batch call failed ({}); falling back to per-memory classify", e.toString());
      return classifyEachTolerant(contents);
    }

    List<Classification> mapped = mapBatchClassifications(dto, contents);
    if (mapped != null) {
      return mapped;
    }
    LOGGER.warn("stage=classify batch result unusable for {} memories; falling back to per-memory classify",
      contents.size());
    return classifyEachTolerant(contents);
  }

  /**
   * Per-memory classification that never throws: an individual call failure stores that memory as a
   * plain {@code fact} (no topic key) rather than aborting the chunk's ingest. Classification is
   * enrichment — losing it for one memory is far better than dropping the memory entirely.
   */
  private List<Classification> classifyEachTolerant(List<String> contents) {
    List<Classification> results = new ArrayList<>(contents.size());
    for (String content : contents) {
      try {
        results.add(classify(content));
      } catch (RuntimeException e) {
        LOGGER.warn("classify failed for one memory ({}); storing it as a plain fact", e.toString());
        results.add(buildClassification(null, null, null, null));
      }
    }
    return results;
  }

  /**
   * Map a batched classify response onto {@code contents} by 1-based index. Returns {@code null} when
   * the response is structurally unusable (caller falls back to per-content classify); a content with
   * no matching item gets the default FACT classification.
   */
  private List<Classification> mapBatchClassifications(BatchClassificationDto dto, List<String> contents) {
    if (dto == null || dto.items() == null || dto.items().isEmpty()) {
      return null;
    }
    Map<Integer, ClassificationItemDto> byIndex = new HashMap<>();
    for (ClassificationItemDto item : dto.items()) {
      if (item != null && item.index() != null) {
        byIndex.putIfAbsent(item.index(), item);
      }
    }
    if (byIndex.isEmpty()) {
      return null;
    }
    List<Classification> results = new ArrayList<>(contents.size());
    for (int i = 0; i < contents.size(); i++) {
      ClassificationItemDto item = byIndex.get(i + 1);
      results.add(item == null
        ? buildClassification(null, null, null, null)
        : buildClassification(item.type(), item.topicKey(), item.interrogativeQueries(), item.payload()));
    }
    return results;
  }

  /**
   * Build a {@link Classification} from raw model fields: default the type to {@code fact}, keep a
   * normalized topic key only for keyed types, strip blank queries, and reduce a payload that is not
   * a JSON object to {@code "{}"}. Shared by single- and batch-classify so both produce identical
   * shapes.
   */
  private Classification buildClassification(String typeWire, String topicKeyRaw,
                                             List<String> queriesRaw, String payloadRaw) {
    MemoryType type = parseTypeOrNull(typeWire);
    if (type == null) {
      type = MemoryType.FACT;
    }
    String topicKey = null;
    if (type == MemoryType.FACT || type == MemoryType.INSTRUCTION) {
      topicKey = normalizeTopicKey(topicKeyRaw);
    }
    List<String> queries = new ArrayList<>();
    if (queriesRaw != null) {
      for (String q : queriesRaw) {
        if (q != null && !q.isBlank()) {
          queries.add(q.strip());
        }
      }
    }
    int queryCap = interrogativeQueriesPerMemory();
    if (queryCap > 0 && queries.size() > queryCap) {
      queries = new ArrayList<>(queries.subList(0, queryCap));
    }
    return new Classification(type, topicKey, List.copyOf(queries), sanitizePayload(payloadRaw));
  }

  private String queryCountInstruction() {
    int exact = interrogativeQueriesPerMemory();
    return exact > 0
      ? "exactly " + exact + " natural-language questions a user might ask that this memory answers"
      : "3 to 5 natural-language questions a user might ask that this memory answers";
  }

  private String candidateLimitInstruction() {
    int cap = maxExtractedCandidatesPerChunk();
    return cap > 0
      ? "Return at most " + cap + " candidates, selecting the most durable and useful when more are available."
      : "";
  }

  private int interrogativeQueriesPerMemory() {
    return properties.ingestion() == null ? 0
      : properties.ingestion().interrogativeQueriesPerMemory();
  }

  private int maxExtractedCandidatesPerChunk() {
    return properties.ingestion() == null ? 0
      : properties.ingestion().maxExtractedCandidatesPerChunk();
  }

  private boolean graphFromExtraction() {
    return properties.ingestion() != null && properties.ingestion().graphFromExtraction();
  }

  private int maxGraphEntitiesPerMemory() {
    return properties.ingestion() == null ? DEFAULT_MAX_GRAPH_ITEMS
      : properties.ingestion().maxGraphEntitiesPerMemory();
  }

  private int maxGraphTriplesPerMemory() {
    return properties.ingestion() == null ? DEFAULT_MAX_GRAPH_ITEMS
      : properties.ingestion().maxGraphTriplesPerMemory();
  }

  private String graphInstruction() {
    return graphFromExtraction()
      ? "- graphEntities / graphTriples: optional grounded graph fragments for this exact statement; "
      + "at most 5 entities and 5 triples, using the same entity/triple fields as graph extraction"
      : "- omit graphEntities and graphTriples";
  }

  @Override
  public GraphFragment extractGraph(String content) {
    if (content == null || content.isBlank()) {
      return GraphFragment.empty();
    }
    String prompt = PromptTemplateLoader.render("extract-graph-single", Map.of(
      "content", content,
      "maxEntities", String.valueOf(maxGraphEntitiesPerMemory()),
      "maxTriples", String.valueOf(maxGraphTriplesPerMemory())));

    GraphDto dto;
    try {
      dto = callExtractionEntity(prompt, "extractGraph", GraphDto.class);
    } catch (RuntimeException e) {
      // Graph extraction is degradable: never escalate. Ingestion stores the memory without a graph.
      LOGGER.warn("graph extraction call failed; returning empty fragment: {}", e.toString());
      return GraphFragment.empty();
    }
    if (dto == null) {
      return GraphFragment.empty();
    }
    return cappedFragment(dto.entities(), dto.triples());
  }

  /**
   * Batch graph extraction over the compact line protocol (see {@code extract-graph-batch.txt}).
   *
   * <p>This stage is decode-bound: its wall time is essentially how many characters the model writes,
   * so the reply is plain {@code E}/{@code T} lines rather than nested JSON. Dropping the repeated
   * {@code "sourceName"}/{@code "sourceType"}/… keys removes roughly ten tokens of pure syntax from
   * every triple, and resolving each triple's endpoint types from the memory's own {@code E} line
   * (instead of restating them per triple) also forces triples to reference declared entities.
   */
  @Override
  public List<GraphFragment> extractGraphAll(List<String> contents) {
    if (contents == null || contents.isEmpty()) {
      return List.of();
    }
    if (contents.size() == 1) {
      // Single item: the structured-output JSON path, which is also the per-item fallback below.
      return List.of(extractGraph(contents.getFirst()));
    }
    StringBuilder numbered = new StringBuilder();
    for (int i = 0; i < contents.size(); i++) {
      numbered.append(i + 1).append(". ").append(contents.get(i)).append('\n');
    }
    String prompt = PromptTemplateLoader.render("extract-graph-batch", Map.of(
      "memories", numbered.toString(),
      "maxEntities", String.valueOf(maxGraphEntitiesPerMemory()),
      "maxTriples", String.valueOf(maxGraphTriplesPerMemory())));

    // One reformat attempt before giving up on the batch. A compact line protocol is not schema-
    // enforced, so its characteristic failure is the model answering in the wrong shape entirely
    // (prose, JSON, an apology) rather than one bad memory — and generation is stochastic enough
    // that asking again usually lands. Paying one extra call here avoids the n-call per-item
    // stampede in the case that actually dominates.
    for (int attempt = 1; attempt <= BATCH_GRAPH_FORMAT_ATTEMPTS; attempt++) {
      String reply;
      try {
        reply = callExtractionText(prompt, "extractGraph");
      } catch (RuntimeException e) {
        // Degradable: never escalate. Extraction failures cost the memory its graph, nothing more.
        LOGGER.warn("graph extraction batch call failed; falling back to per-memory: {}", e.toString());
        return extractGraphPerItem(contents);
      }
      List<GraphFragment> mapped = parseCompactGraphs(reply, contents.size());
      if (mapped != null) {
        return mapped;
      }
      LOGGER.warn("graph extraction batch reply was unusable ({} chars, attempt {}/{})",
        reply.length(), attempt, BATCH_GRAPH_FORMAT_ATTEMPTS);
    }
    return extractGraphPerItem(contents);
  }

  private List<GraphFragment> extractGraphPerItem(List<String> contents) {
    List<GraphFragment> results = new ArrayList<>(contents.size());
    for (String content : contents) {
      try {
        results.add(extractGraph(content));
      } catch (RuntimeException e) {
        results.add(GraphFragment.empty());
      }
    }
    return results;
  }

  /**
   * Parse a compact batch reply into fragments aligned 1:1 with the {@code count} inputs.
   *
   * <p>Returns {@code null} when the reply is structurally unusable, which makes the caller retry.
   * A <em>short</em> reply with no recognizable line is instead read as an honest "nothing to
   * extract here" and mapped to empty fragments: the prompt tells the model to omit barren memories,
   * and treating that as a parse failure would put a genuinely empty batch through a retry and then
   * a per-memory sweep for no reason. Anything longer without a usable line really is malformed
   * (prose, JSON, an apology) and is worth another attempt.
   */
  private List<GraphFragment> parseCompactGraphs(String reply, int count) {
    if (reply == null) {
      return null;
    }
    Map<Integer, List<EntityDto>> entitiesByIndex = new HashMap<>();
    Map<Integer, List<String[]>> triplesByIndex = new HashMap<>();
    boolean recognized = false;

    for (String rawLine : reply.split("\\R")) {
      String line = rawLine.strip();
      if (line.length() < 2) {
        continue;
      }
      char kind = Character.toUpperCase(line.charAt(0));
      if (kind != 'E' && kind != 'T') {
        continue;
      }
      String[] parts = line.substring(1).split("\\|", -1);
      Integer index = parseIndex(parts[0]);
      if (index == null || index < 1 || index > count) {
        continue;
      }
      if (kind == 'E') {
        List<EntityDto> entities = entitiesByIndex.computeIfAbsent(index, _ -> new ArrayList<>());
        for (int i = 1; i < parts.length; i++) {
          EntityDto entity = parseEntityToken(parts[i]);
          if (entity != null) {
            entities.add(entity);
            recognized = true;
          }
        }
      } else if (parts.length >= 4) {
        String source = parts[1].strip();
        String relation = parts[2].strip();
        String target = parts[3].strip();
        if (!source.isEmpty() && !relation.isEmpty() && !target.isEmpty()) {
          triplesByIndex.computeIfAbsent(index, _ -> new ArrayList<>())
            .add(new String[]{source, relation, target});
          recognized = true;
        }
      }
    }

    if (!recognized) {
      return reply.strip().length() <= EMPTY_GRAPH_REPLY_MAX_CHARS ? emptyFragments(count) : null;
    }

    List<GraphFragment> results = new ArrayList<>(count);
    for (int i = 1; i <= count; i++) {
      List<EntityDto> entities = entitiesByIndex.getOrDefault(i, List.of());
      results.add(cappedFragment(entities, resolveTriples(triplesByIndex.getOrDefault(i, List.of()), entities)));
    }
    return results;
  }

  /**
   * Build a fragment with the per-memory entity/triple caps applied. Enforcing them here as well as
   * in the prompt means a model that ignores the instruction still cannot inflate the graph.
   */
  private GraphFragment cappedFragment(List<EntityDto> entities, List<TripleDto> triples) {
    return toGraphFragment(
      limit(entities, maxGraphEntitiesPerMemory()),
      limit(triples, maxGraphTriplesPerMemory()));
  }

  /**
   * Build a deduplicated {@link GraphFragment} from raw entity/triple DTOs (entities deduped by
   * normalized type+name; triples by normalized source|relation|target). Shared by single- and
   * batch-graph extraction so both produce identical shapes.
   */
  private GraphFragment toGraphFragment(List<EntityDto> entityDtos, List<TripleDto> tripleDtos) {
    List<Entity> entities = new ArrayList<>();
    LinkedHashSet<String> seenEntities = new LinkedHashSet<>();
    if (entityDtos != null) {
      for (EntityDto e : entityDtos) {
        if (e == null) {
          continue;
        }
        String name = EntityNormalizer.normalizeName(e.name());
        if (name.isEmpty()) {
          continue;
        }
        String type = EntityNormalizer.normalizeType(e.type());
        if (seenEntities.add(type + " " + name)) {
          entities.add(Entity.of(type, name, "{}"));
        }
      }
    }

    // Deduplicate triples by normalized (source, relation, target).
    List<GraphFragment.EdgeTriple> triples = new ArrayList<>();
    LinkedHashSet<String> seenTriples = new LinkedHashSet<>();
    if (tripleDtos != null) {
      for (TripleDto t : tripleDtos) {
        if (t == null) {
          continue;
        }
        String sourceName = EntityNormalizer.normalizeName(t.sourceName());
        String targetName = EntityNormalizer.normalizeName(t.targetName());
        String relation = EntityNormalizer.normalizeRelation(t.relation());
        if (sourceName.isEmpty() || targetName.isEmpty() || relation.isEmpty()) {
          continue;
        }
        String sourceType = EntityNormalizer.normalizeType(t.sourceType());
        String targetType = EntityNormalizer.normalizeType(t.targetType());
        if (seenTriples.add(sourceName + "|" + relation + "|" + targetName)) {
          triples.add(new GraphFragment.EdgeTriple(sourceName, sourceType, relation, targetName, targetType));
        }
      }
    }

    return new GraphFragment(entities, triples);
  }

  @Override
  public QueryAnalysis analyzeQuery(String query) {
    if (query == null || query.isBlank()) {
      return new QueryAnalysis(List.of(), List.of(), List.of(), null);
    }
    String prompt = PromptTemplateLoader.render("analyze-query", Map.of("query", query));

    QueryAnalysisDto dto;
    try {
      dto = callExtractionEntity(prompt, "analyzeQuery", QueryAnalysisDto.class);
    } catch (RuntimeException e) {
      throw new ModelUnavailableException("model query analysis failed", e);
    }
    if (dto == null) {
      return new QueryAnalysis(List.of(), List.of(), List.of(), null);
    }

    List<String> topicKeys = new ArrayList<>();
    LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
    if (dto.topicKeys() != null) {
      for (String raw : dto.topicKeys()) {
        String key = normalizeTopicKey(raw);
        if (key != null && seenKeys.add(key)) {
          topicKeys.add(key);
        }
      }
    }

    List<String> ftsTerms = new ArrayList<>();
    LinkedHashSet<String> seenTerms = new LinkedHashSet<>();
    if (dto.ftsTerms() != null) {
      for (String raw : dto.ftsTerms()) {
        String term = blankToNull(raw);
        if (term != null) {
          term = term.toLowerCase(Locale.ROOT);
          if (seenTerms.add(term)) {
            ftsTerms.add(term);
          }
        }
      }
    }

    // Normalize entity names to match the stored graph (lowercased, collapsed, aliased), deduped.
    List<String> entities = new ArrayList<>();
    LinkedHashSet<String> seenEntities = new LinkedHashSet<>();
    if (dto.entities() != null) {
      for (String raw : dto.entities()) {
        String name = EntityNormalizer.normalizeName(raw);
        if (!name.isEmpty() && seenEntities.add(name)) {
          entities.add(name);
        }
      }
    }

    return new QueryAnalysis(topicKeys, ftsTerms, entities, blankToNull(dto.hydeStatement()));
  }

  @Override
  public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
    return synthesizeRecall(query, candidates, List.of());
  }

  @Override
  public String synthesizeRecall(String query, List<RecallCandidate> candidates,
                                 List<TemporalFact> temporalFacts) {
    return synthesizeRecall(query, candidates, temporalFacts, List.of());
  }

  @Override
  public String synthesizeRecall(String query, List<RecallCandidate> candidates,
                                 List<TemporalFact> temporalFacts, List<GraphEvidence> graphEvidence) {
    List<RecallCandidate> safeCandidates = candidates == null ? List.of() : candidates;
    String context = safeCandidates.stream()
      .map(c -> "- " + c.memory().content())
      .collect(Collectors.joining("\n"));
    if (context.isBlank()) {
      context = "(no candidate memories were retrieved)";
    }

    // Temporal facts are computed deterministically in Java and injected as ground truth;
    // the model must use them verbatim and never do its own date arithmetic.
    List<TemporalFact> safeTemporal = temporalFacts == null ? List.of() : temporalFacts;
    String temporalBlock = safeTemporal.isEmpty()
      ? "(none)"
      : safeTemporal.stream().map(f -> "- " + f.render()).collect(Collectors.joining("\n"));

    // Code-graph evidence is extracted deterministically from the indexed source, so like the
    // temporal facts it is injected as ground truth rather than recalled memory text.
    List<GraphEvidence> safeEvidence = graphEvidence == null ? List.of() : graphEvidence;
    String graphBlock = safeEvidence.isEmpty()
      ? "(none)"
      : safeEvidence.stream().map(e -> "- " + e.render()).collect(Collectors.joining("\n"));

    String prompt = PromptTemplateLoader.render("synthesize-recall", Map.of(
      "query", query,
      "temporalFacts", temporalBlock,
      "graphEvidence", graphBlock,
      "memories", context));

    try {
      ChatResponse chatResponse = retry.execute("synthesizeRecall", () -> synthesisChatClient.prompt()
        .user(prompt)
        .options(reasoningOptions("synthesizeRecall", properties.model().synthesisModel()))
        .call()
        .chatResponse());
      logTokenUsage("synthesizeRecall", chatResponse);
      if (chatResponse == null || chatResponse.getResult() == null) {
        return "";
      } else {
        chatResponse.getResult();
      }
      return chatResponse.getResult().getOutput().getText();
    } catch (RuntimeException e) {
      throw new ModelUnavailableException("model synthesis failed", e);
    }
  }

  @Override
  public String summarizeCode(CodeSummaryInput input) {
    String prompt = switch (input.level()) {
      case FILE -> PromptTemplateLoader.render("summarize-code-file", Map.of(
        "path", input.subjectPath(),
        "language", input.language() == null ? "unknown" : input.language(),
        "outlines", joinedOrNone(input.outlines()),
        "source", input.source() == null || input.source().isBlank() ? "(no source available)" : input.source()));
      case MODULE -> PromptTemplateLoader.render("summarize-code-module", Map.of(
        "module", input.subjectPath(),
        "outlines", joinedOrNone(input.outlines()),
        "childSummaries", joinedOrNone(input.childSummaries())));
      case ARCHITECTURE -> PromptTemplateLoader.render("summarize-code-architecture", Map.of(
        "repository", input.subjectPath(),
        "modules", joinedOrNone(input.childSummaries().isEmpty() ? input.outlines() : input.childSummaries())));
    };

    try {
      ChatResponse chatResponse = retry.execute("summarizeCode", () -> synthesisChatClient.prompt()
        .user(prompt)
        .options(reasoningOptions("summarizeCode", properties.model().synthesisModel()))
        .call()
        .chatResponse());
      logTokenUsage("summarizeCode", chatResponse);
      if (chatResponse == null || chatResponse.getResult() == null) {
        return "";
      }
      return chatResponse.getResult().getOutput().getText();
    } catch (RuntimeException e) {
      throw new ModelUnavailableException("model code summarization failed", e);
    }
  }

  /**
   * A synthesis-tier call returning the raw assistant text. Goes through {@code chatResponse()}
   * rather than {@code content()} so the reply's token usage is recorded like every other stage —
   * the judge stages are eval-only but not free, and on a benchmark run they are a large share of the
   * spend.
   */
  private String callSynthesisText(String prompt, String stage) {
    ChatResponse chatResponse = retry.execute(stage, () -> synthesisChatClient.prompt()
      .user(prompt)
      .options(reasoningOptions(stage, properties.model().synthesisModel()))
      .call()
      .chatResponse());

    logTokenUsage(stage, chatResponse);

    if (chatResponse == null || chatResponse.getResult() == null
      || chatResponse.getResult().getOutput() == null) {
      return "";
    }
    String text = chatResponse.getResult().getOutput().getText();
    return text == null ? "" : text;
  }

  @Override
  public AnswerVerdict judgeAnswer(String question, String expectedAnswer, String actualAnswer) {
    String prompt = PromptTemplateLoader.render("judge-answer", Map.of(
      "question", question == null ? "" : question,
      "expected", expectedAnswer == null ? "" : expectedAnswer,
      "actual", actualAnswer == null ? "" : actualAnswer));

    LOGGER.info("judge question='{}' expected='{}'", truncate(question, 80), truncate(expectedAnswer, 80));
    LOGGER.info("judge actual='{}'", truncate(actualAnswer, 120));

    try {
      String response = callSynthesisText(prompt, "judgeAnswer");

      AnswerVerdict verdict = parseVerdict(response, expectedAnswer, actualAnswer);

      LOGGER.info("judge verdict={} raw='{}'", verdict, truncate(response, 40));

      return verdict;
    } catch (RuntimeException e) {
      LOGGER.warn("judge call failed ({}); falling back to exact match", e.getMessage());

      AnswerVerdict verdict = ModelGateway.super.judgeAnswer(question, expectedAnswer, actualAnswer);

      LOGGER.info("judge verdict={} (exact-match fallback)", verdict);

      return verdict;
    }
  }

  @Override
  public boolean judgeEvidenceSupport(String question, String expectedAnswer, List<String> evidence) {
    List<String> notes = evidence == null ? List.of() : evidence;
    if (notes.isEmpty()) {
      return false;
    }
    String prompt = PromptTemplateLoader.render("judge-evidence-support", Map.of(
      "question", question == null ? "" : question,
      "expected", expectedAnswer == null ? "" : expectedAnswer,
      "evidence", joinedOrNone(notes)));

    try {
      String response = callSynthesisText(prompt, "judgeEvidenceSupport");

      boolean supported = response != null && response.strip().toLowerCase(Locale.ROOT).startsWith("true");

      LOGGER.info("evidence support={} over {} notes for '{}'",
        supported, notes.size(), truncate(question, 60));

      return supported;
    } catch (RuntimeException e) {
      LOGGER.warn("evidence-support call failed ({}); falling back to containment", e.getMessage());
      return ModelGateway.super.judgeEvidenceSupport(question, expectedAnswer, notes);
    }
  }

  @Override
  public float[] embed(String text) {
    try {
      EmbeddingResponse response = retry.execute("embed",
        () -> embeddingModel.embedForResponse(List.of(text == null ? "" : text)));
      logEmbeddingUsage(response);
      response.getResult();
      return response.getResult().getOutput();
    } catch (RuntimeException e) {
      throw new ModelUnavailableException("Model embedding failed: " + e.getMessage(), e);
    }
  }

  /**
   * Cheap reachability probe: HTTP GET to the provider base URL with a short timeout.
   * Returns {@code true} on any HTTP response (server is up, regardless of model state);
   * {@code false} on any IO failure. Never invokes a model or generates tokens.
   * The provider base URL is never echoed in the health response.
   */
  @Override
  public boolean isModelProviderReachable() {
    String baseUrl = properties.provider().baseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      return false;
    }
    try {
      HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl).toURL().openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(2000);
      conn.setReadTimeout(2000);
      conn.setInstanceFollowRedirects(false);
      int code = conn.getResponseCode();
      conn.disconnect();
      // Any HTTP response (including 404) means the server is reachable.
      return code > 0;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Report the model names the provider currently has available, delegating to the configured
   * {@link ModelProviderAdapter} since discovery is dialect-specific (Ollama et al. expose
   * {@code GET /v1/models}; Azure exposes deployments at a different, api-version'd path and is
   * skipped). Read-only, log-only first-run guidance: never invokes a model or throws.
   */
  @Override
  public Set<String> availableModels() {
    return providerAdapter.availableModels(properties.provider());
  }

  /**
   * Structured-output target for one unified-extraction candidate: content plus its
   * classification fields, all from the single extraction call. Shape v1.
   */
  private record UnifiedCandidateDto(String content, String type, String topicKey,
                                     List<String> interrogativeQueries, String payload,
                                     List<EntityDto> graphEntities, List<TripleDto> graphTriples) {
  }

  /**
   * Wrapper tolerated by the unified-extraction parser when the model emits
   * {@code {"candidates":[...]}} instead of a bare array. Shape v1.
   */
  private record UnifiedCandidateList(List<UnifiedCandidateDto> candidates) {
  }

  /**
   * Structured-output target for {@link #verify}. Shape v1.
   */
  private record VerificationDto(String verdict, String content, String reason) {
  }

  /**
   * Structured-output target for one item of {@link #verifyAll}; {@code index} is the 1-based
   * candidate number echoed by the model. Shape v1.
   */
  private record VerificationItemDto(Integer index, String verdict, String content, String reason) {
  }

  /**
   * Wrapper so Spring AI binds a top-level object for the batched verify pass. Shape v1.
   */
  private record BatchVerificationDto(List<VerificationItemDto> verdicts) {
  }

  /**
   * Structured-output target for {@link #classify}. Shape v1.
   */
  private record ClassificationDto(String type, String topicKey, List<String> interrogativeQueries, String payload) {
  }

  /**
   * Structured-output target for one item of {@link #classifyAll}; {@code index} is the 1-based
   * memory number echoed by the model. Shape v1.
   */
  private record ClassificationItemDto(Integer index, String type, String topicKey,
                                       List<String> interrogativeQueries, String payload) {
  }

  /**
   * Wrapper so Spring AI binds a top-level object for the batched classify pass. Shape v1.
   */
  private record BatchClassificationDto(List<ClassificationItemDto> items) {
  }

  /**
   * Structured-output target for {@link #analyzeQuery}. Shape v1 (+ entities).
   */
  private record QueryAnalysisDto(
    List<String> topicKeys, List<String> ftsTerms, List<String> entities, String hydeStatement) {
  }

  /**
   * Structured-output target for one extracted relationship triple. Shape v1.
   */
  private record TripleDto(
    String sourceName, String sourceType, String relation, String targetName, String targetType) {
  }

  /**
   * Structured-output target for {@link #extractGraph}. Shape v1.
   */
  private record GraphDto(List<EntityDto> entities, List<TripleDto> triples) {
  }

  /**
   * Structured-output target for one extracted entity. Shape v1.
   */
  private record EntityDto(String name, String type) {
  }
}
