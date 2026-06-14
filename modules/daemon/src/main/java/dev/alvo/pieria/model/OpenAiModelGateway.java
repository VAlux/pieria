package dev.alvo.pieria.model;


import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.ingestion.model.Chunk;
import dev.alvo.pieria.ingestion.model.Classification;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.graph.EntityNormalizer;
import dev.alvo.pieria.ingestion.model.ExtractedCandidate;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.retrieval.model.QueryAnalysis;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.TemporalFact;
import dev.alvo.pieria.ingestion.model.VerificationResult;
import dev.alvo.pieria.ingestion.model.VerificationVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

  private final ChatClient extractionChatClient;
  private final ChatClient synthesisChatClient;
  private final EmbeddingModel embeddingModel;
  private final PieriaProperties properties;

  public OpenAiModelGateway(@Qualifier("extractionChatClient") ChatClient extractionChatClient,
                            @Qualifier("synthesisChatClient") ChatClient synthesisChatClient,
                            EmbeddingModel embeddingModel,
                            PieriaProperties properties) {
    this.extractionChatClient = extractionChatClient;
    this.synthesisChatClient = synthesisChatClient;
    this.embeddingModel = embeddingModel;
    this.properties = properties;
  }

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.strip();
  }

  /**
   * Run a structured-output chat call on the extraction client, log the per-stage token usage at
   * INFO, and return the bound entity. Usage capture is best-effort and never NPEs: if the provider
   * does not report usage, token counts are logged as zero. {@code stage} names the pipeline stage
   * (extract, extractDetail, verify, classify, analyzeQuery) for the log line.
   */
  private <T> T callExtractionEntity(String prompt, String stage, Class<T> type) {
    var response = extractionChatClient.prompt()
      .user(prompt)
      .call()
      .responseEntity(type);

    logTokenUsage(stage, response.getResponse());
    return response.getEntity();
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
  }

  private static int nullToZero(Integer value) {
    return value == null ? 0 : value;
  }

  @Override
  public List<Memory> extractMemories(List<Message> messages) {
    if (messages == null || messages.isEmpty()) {
      return List.of();
    }

    String sessionId = messages.getFirst().sessionId();
    String transcript = messages.stream()
      .map(m -> m.role() + ": " + m.content())
      .collect(Collectors.joining("\n"));

    String prompt = """
      Extract durable, long-lived memories from the following conversation transcript.
      Only capture information worth remembering across future sessions: stable facts,
      notable events, standing instructions/preferences, and outstanding tasks.
      
      For each memory set:
      - type: one of fact, event, instruction, task
      - content: a concise, self-contained statement of the memory
      - topicKey: a short stable key for the subject (or empty if none)
      - payload: a JSON object string with any extra structured fields (or "{}")
      
      Do not invent information. If nothing is worth remembering, return an empty list.
      
      Transcript:
      %s
      """.formatted(transcript);

    ExtractionResult result;
    try {
      result = callExtractionEntity(prompt, "extractMemories", ExtractionResult.class);
    } catch (RuntimeException e) {
      throw new ModelUnavailableException("model extraction failed", e);
    }

    if (result == null || result.memories() == null) {
      return List.of();
    }

    List<Memory> memories = new ArrayList<>();
    for (ExtractedMemory extracted : result.memories()) {
      if (extracted == null || extracted.content() == null || extracted.content().isBlank()) {
        continue;
      }
      MemoryType type;
      try {
        type = MemoryType.fromWire(extracted.type());
      } catch (IllegalArgumentException | NullPointerException ignored) {
        continue;
      }
      memories.add(Memory.of(type, extracted.content().strip(), sessionId,
        blankToNull(extracted.topicKey()), extracted.payload()));
    }
    return memories;
  }

  // --- Pipeline stages: extraction, verification, classification ----
  // All stages below run on the small/fast extraction client for structured output.
  // The prompts and the structured-output record shapes below are part of the stable contract
  // the ingestion + test layers rely on; bump a version note here if the JSON shapes change.
  // Prompt/shape version: v1.

  @Override
  public List<ExtractedCandidate> extract(Chunk chunk) {
    if (chunk == null || chunk.transcript() == null || chunk.transcript().isBlank()) {
      return List.of();
    }
    String prompt = """
      You are the FULL-PASS extractor of a memory pipeline. Read the conversation chunk below
      and extract durable, long-lived candidate memories worth remembering across future sessions:
      stable facts, notable events, standing instructions/preferences, and outstanding tasks.
      
      For each candidate set:
      - content: a concise, self-contained declarative statement
      - suggestedType: your best guess of one of fact, event, instruction, task (or empty if unsure)
      
      Do not invent information not present in the chunk. If nothing is worth remembering,
      return an empty array.
      
      Respond with ONLY a JSON array — no markdown, no explanation, no extra text.
      Format: [{"content": "...", "suggestedType": "..."}]
      Empty result: []
      
      Chunk transcript:
      %s
      """.formatted(chunk.transcript());
    return callExtraction(prompt, chunk.index(), "extract");
  }

  @Override
  public List<ExtractedCandidate> extractDetail(Chunk chunk) {
    if (chunk == null || chunk.transcript() == null || chunk.transcript().isBlank()) {
      return List.of();
    }
    String prompt = """
      You are the DETAIL-PASS extractor of a memory pipeline. The broad full pass tends to miss
      concrete values. From the conversation chunk below, extract candidates that capture concrete,
      specific values: names, versions, prices, file paths, URLs, entity attributes, and dates.
      
      For each candidate set:
      - content: a concise, self-contained declarative statement carrying the concrete value
      - suggestedType: your best guess of one of fact, event, instruction, task (or empty if unsure)
      
      Do not invent information not present in the chunk. If there are no concrete values worth
      capturing, return an empty array.
      
      Respond with ONLY a JSON array — no markdown, no explanation, no extra text.
      Format: [{"content": "...", "suggestedType": "..."}]
      Empty result: []
      
      Chunk transcript:
      %s
      """.formatted(chunk.transcript());
    return callExtraction(prompt, chunk.index(), "extractDetail");
  }

  private List<ExtractedCandidate> callExtraction(String prompt, int chunkIndex, String stage) {
    ChatResponse chatResponse;
    try {
      chatResponse = extractionChatClient.prompt()
        .user(prompt)
        .call()
        .chatResponse();
    } catch (RuntimeException e) {
      throw new ModelUnavailableException("model " + stage + " failed", e);
    }
    logTokenUsage(stage, chatResponse);

    String rawText = chatResponse != null && chatResponse.getResult() != null
      && chatResponse.getResult().getOutput() != null
      ? chatResponse.getResult().getOutput().getText() : null;

    List<ExtractedCandidate> candidates = new ArrayList<>();
    for (RawCandidate raw : parseCandidatesResilient(rawText, stage)) {
      if (raw == null || raw.content() == null || raw.content().isBlank()) {
        continue;
      }
      MemoryType suggested = parseTypeOrNull(raw.suggestedType());
      candidates.add(new ExtractedCandidate(raw.content().strip(), suggested, chunkIndex, stage));
    }
    return candidates;
  }

  /**
   * Parses model output into a list of {@link RawCandidate}s tolerating two shapes:
   * the expected wrapped object {@code {"candidates":[...]}} and a bare array {@code [...]}.
   * Also strips markdown code fences that some models emit around JSON.
   */
  private static final Pattern MD_CONTENT = Pattern.compile(
    "(?im)^[-*\\d.]*\\s*\\*{0,2}(?:content)\\*{0,2}:?\\s*(.+)$");
  private static final Pattern MD_TYPE = Pattern.compile(
    "(?im)^[-*\\s]*\\*{0,2}suggested(?:type)?\\*{0,2}:?\\s*([a-z/]+)",
    Pattern.CASE_INSENSITIVE);

  private List<RawCandidate> parseCandidatesResilient(String rawText, String stage) {
    if (rawText == null || rawText.isBlank()) {
      return List.of();
    }
    String json = stripCodeFences(rawText);

    // Try wrapped object {"candidates": [...]}
    try {
      CandidateList wrapped = objectMapper.readValue(json, CandidateList.class);
      if (wrapped != null && wrapped.candidates() != null) {
        return wrapped.candidates();
      }
    } catch (Exception ignored) {
    }

    // Try bare array [...]
    try {
      return objectMapper.readValue(json, new TypeReference<List<RawCandidate>>() {
      });
    } catch (Exception ignored) {
    }

    // Fallback: parse markdown bullet/numbered output the model emits when it ignores JSON instructions
    List<RawCandidate> markdown = parseMarkdownCandidates(rawText);
    if (!markdown.isEmpty()) {
      return markdown;
    }

    LOGGER.warn("stage={} model returned unparseable candidates output; treating as empty. Raw response: {}", stage, rawText);
    return List.of();
  }

  private static List<RawCandidate> parseMarkdownCandidates(String text) {
    List<RawCandidate> candidates = new ArrayList<>();
    String[] lines = text.split("\n");
    String pendingContent = null;
    for (String line : lines) {
      Matcher cm = MD_CONTENT.matcher(line);
      if (cm.find()) {
        pendingContent = cm.group(1).strip().replaceAll("\\*+", "").strip();
        continue;
      }
      if (pendingContent != null) {
        Matcher tm = MD_TYPE.matcher(line);
        if (tm.find()) {
          String rawType = tm.group(1).strip().toLowerCase(Locale.ROOT);
          // normalize "instruction/preference" → "instruction", etc.
          String type = rawType.contains("instruction") ? "instruction"
            : rawType.contains("event") ? "event"
              : rawType.contains("task") ? "task"
                : rawType.contains("fact") ? "fact"
                  : rawType;
          candidates.add(new RawCandidate(pendingContent, type));
          pendingContent = null;
        }
      }
    }
    return candidates;
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

  @Override
  public VerificationResult verify(ExtractedCandidate candidate, String transcript) {
    if (candidate == null || candidate.content() == null || candidate.content().isBlank()) {
      return new VerificationResult(VerificationVerdict.DROP, "", "empty candidate");
    }
    String safeTranscript = transcript == null ? "" : transcript;
    String prompt = """
      You are the VERIFIER of a memory pipeline. Check the candidate memory against the source
      transcript and return one verdict:
      - pass: the candidate is fully supported by the transcript; keep content unchanged
      - correct: the candidate is mostly right but needs a factual fix; return corrected content
      - drop: the candidate is unsupported, hallucinated, or too ambiguous to keep
      
      Set:
      - verdict: one of pass, correct, drop
      - content: for pass echo the original; for correct give the fixed statement; for drop empty
      - reason: a short justification
      
      Candidate:
      %s
      
      Source transcript:
      %s
      """.formatted(candidate.content(), safeTranscript);

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
    String content = switch (verdict) {
      case PASS -> candidate.content();
      case CORRECT -> blankToNull(dto.content()) == null ? candidate.content() : dto.content().strip();
      case DROP -> "";
    };
    return new VerificationResult(verdict, content, blankToNull(dto.reason()));
  }

  @Override
  public Classification classify(String content) {
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be blank");
    }
    String prompt = """
      You are the CLASSIFIER + enricher of a memory pipeline. For the verified memory below assign:
      - type: one of fact, event, instruction, task
      - topicKey: ONLY for fact and instruction, a short normalized subject key
        (lowercase, words joined by '.', e.g. "user.editor", "db.engine"); empty for event/task
      - interrogativeQueries: 3 to 5 natural-language questions a user might ask that this memory
        answers (interrogative search queries)
      - payload: a JSON object string of extra structured fields, or "{}"
      
      Memory content:
      %s
      """.formatted(content);

    ClassificationDto dto;
    try {
      dto = callExtractionEntity(prompt, "classify", ClassificationDto.class);
    } catch (RuntimeException e) {
      throw new ModelUnavailableException("model classification failed", e);
    }
    MemoryType type = dto == null ? null : parseTypeOrNull(dto.type());
    if (type == null) {
      type = MemoryType.FACT;
    }
    String topicKey = null;
    if ((type == MemoryType.FACT || type == MemoryType.INSTRUCTION) && dto != null) {
      topicKey = normalizeTopicKey(dto.topicKey());
    }
    List<String> queries = new ArrayList<>();
    if (dto != null && dto.interrogativeQueries() != null) {
      for (String q : dto.interrogativeQueries()) {
        if (q != null && !q.isBlank()) {
          queries.add(q.strip());
        }
      }
    }
    String payload = (dto == null || blankToNull(dto.payload()) == null) ? "{}" : dto.payload().strip();
    return new Classification(type, topicKey, List.copyOf(queries), payload);
  }

  @Override
  public GraphFragment extractGraph(String content) {
    if (content == null || content.isBlank()) {
      return GraphFragment.empty();
    }
    String prompt = """
      You are the GRAPH EXTRACTOR of a memory pipeline. From the verified memory below, extract:
      - entities: the distinct named entities it mentions. For each: name, and type (one of
        person, project, tool, file, concept).
      - triples: the explicit relationships among those entities, as
        (sourceName, sourceType, relation, targetName, targetType). relation is a short lowercase
        verb phrase, e.g. "uses", "depends on", "runs on", "owns".

      Ground everything strictly in the memory content; do not invent entities or relations. If
      nothing is worth extracting, return empty arrays.

      Memory content:
      %s
      """.formatted(content);

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

    List<Entity> entities = new ArrayList<>();
    LinkedHashSet<String> seenEntities = new LinkedHashSet<>();
    if (dto.entities() != null) {
      for (EntityDto e : dto.entities()) {
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
    if (dto.triples() != null) {
      for (TripleDto t : dto.triples()) {
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

  @Override
  public QueryAnalysis analyzeQuery(String query) {
    if (query == null || query.isBlank()) {
      return new QueryAnalysis(List.of(), List.of(), List.of(), null);
    }
    String prompt = """
      You are the QUERY ANALYZER of a memory retrieval pipeline. Given a recall query,
      produce the inputs the retrieval channels need:
      - topicKeys: 1 to 5 ranked candidate subject keys, most likely first. Each is a short
        normalized key (lowercase, words joined by '.', e.g. "user.editor", "db.engine").
      - ftsTerms: the salient keyword terms from the query EXPANDED with close synonyms and
        common alternate spellings, for full-text search. Single words, lowercase.
      - entities: the named entities (people, projects, tools, files, concepts) referenced in the
        query, as short names, for relationship-graph lookup. Empty if none are named.
      - hydeStatement: one plausible declarative sentence that would directly ANSWER the query
        (a hypothetical answer), used for HyDE vector search.

      Do not answer the question for real; just produce a plausible hypothetical answer sentence.

      Query:
      %s
      """.formatted(query);

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

    String prompt = """
      You answer strictly from the remembered memories below; never add facts they do not contain.
      Interpret the query generously: it may be a full question, a phrase, or a single keyword, so
      infer the subject it points at and gather every memory bearing on that subject. Count a memory
      as relevant when it concerns the query's subject, even if it states a related fact, rule, or
      detail rather than a direct answer. Then answer concisely, grounding each claim in those
      memories.
      Declare insufficient memory evidence only when no memory bears on the query's subject at all;
      brevity or vagueness of the query is never itself a reason to refuse.
      The pre-computed temporal facts are authoritative: use them verbatim and never perform your
      own date or duration arithmetic.
      
      Query:
      %s
      
      Pre-computed temporal facts:
      %s
      
      Remembered memories:
      %s
      """.formatted(query, temporalBlock, context);

    try {
      ChatResponse chatResponse = synthesisChatClient.prompt()
        .user(prompt)
        .call()
        .chatResponse();
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
  public boolean judgeAnswerFaithfulness(String question, String expectedAnswer, String actualAnswer) {
    String prompt = """
      You are a strict answer equivalence judge. Decide whether the ACTUAL answer conveys the same
      information as the EXPECTED answer for the given question.
      
      Rules:
      - Respond "true" if the actual answer contains the expected answer or is semantically equivalent.
      - Respond "false" if the actual answer is wrong, incomplete, or claims insufficient evidence
        when the expected answer exists.
      - Ignore differences in phrasing, capitalization, or minor wording.
      
      Question: %s
      Expected: %s
      Actual: %s
      
      Respond with only "true" or "false".
      """.formatted(
      question == null ? "" : question,
      expectedAnswer == null ? "" : expectedAnswer,
      actualAnswer == null ? "" : actualAnswer);

    LOGGER.info("judge question='{}' expected='{}'", truncate(question, 80), truncate(expectedAnswer, 80));
    LOGGER.info("judge actual='{}'", truncate(actualAnswer, 120));

    try {
      String response = synthesisChatClient.prompt()
        .user(prompt)
        .call()
        .content();

      boolean verdict = response != null && response.strip().toLowerCase(Locale.ROOT).startsWith("true");

      LOGGER.info("judge verdict={} raw='{}'", verdict, truncate(response, 40));

      return verdict;
    } catch (RuntimeException e) {
      LOGGER.warn("judge call failed ({}); falling back to exact match", e.getMessage());

      String normExpected = expectedAnswer == null ? "" : expectedAnswer.strip().toLowerCase(Locale.ROOT);
      String normActual = actualAnswer == null ? "" : actualAnswer.strip().toLowerCase(Locale.ROOT);
      boolean verdict = normExpected.equals(normActual);

      LOGGER.info("judge verdict={} (exact-match fallback)", verdict);

      return verdict;
    }
  }

  private static String truncate(String s, int max) {
    if (s == null) return "(null)";
    String stripped = s.strip().replace('\n', ' ');
    return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
  }

  @Override
  public float[] embed(String text) {
    try {
      EmbeddingResponse response = embeddingModel.embedForResponse(List.of(text == null ? "" : text));
      logEmbeddingUsage(response);
      response.getResult();
      return response.getResult().getOutput();
    } catch (RuntimeException e) {
      throw new ModelUnavailableException("Model embedding failed: " + e.getMessage(), e);
    }
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
    LOGGER.info("model stage=embed promptTokens={} completionTokens={} totalTokens={}",
      nullToZero(usage.getPromptTokens()),
      nullToZero(usage.getCompletionTokens()),
      nullToZero(usage.getTotalTokens()));
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
   * Query the provider's OpenAI-compatible {@code GET /v1/models} endpoint and return the set of
   * available model names (the {@code data[].id} values). Read-only: never invokes a model or
   * generates tokens. Returns an empty set on any IO/parse failure and never throws, so first-run
   * guidance degrades gracefully when the provider is down. Model names are returned verbatim
   * (e.g. {@code "llama3.2:3b"}, {@code "gpt-4o"}).
   *
   * <p>Azure OpenAI does not expose models at {@code /v1/models} (it lists <em>deployments</em> at a
   * different, api-version'd path), so in {@code azure} mode this returns an empty set and skips the
   * probe. First-run guidance is log-only, so an empty set degrades gracefully.
   */
  @Override
  public Set<String> availableModels() {
    if (properties.provider().isAzure()) {
      return Set.of();
    }
    String baseUrl = properties.provider().baseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      return Set.of();
    }
    String modelsUrl = baseUrl.replaceAll("/+$", "") + "/v1/models";
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) URI.create(modelsUrl).toURL().openConnection();
      conn.setRequestMethod("GET");
      String apiKey = properties.provider().apiKey();
      if (apiKey != null && !apiKey.isBlank()) {
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
      }
      conn.setConnectTimeout(2000);
      conn.setReadTimeout(2000);
      conn.setInstanceFollowRedirects(false);
      int code = conn.getResponseCode();
      if (code < 200 || code >= 300) {
        return Set.of();
      }
      LinkedHashSet<String> names = new LinkedHashSet<>();
      try (InputStream in = conn.getInputStream()) {
        JsonNode root = objectMapper.readTree(in);
        JsonNode models = root.get("data");
        if (models != null && models.isArray()) {
          for (JsonNode model : models) {
            JsonNode id = model.get("id");
            if (id != null && !id.asString().isBlank()) {
              names.add(id.asString().strip());
            }
          }
        }
      }
      return Set.copyOf(names);
    } catch (Exception e) {
      // Never throw: missing/unreachable provider just yields no available models.
      return Set.of();
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  /**
   * Structured-output target for one extracted memory.
   */
  private record ExtractedMemory(String type, String content, String topicKey, String payload) {
  }

  /**
   * Wrapper so Spring AI can bind a top-level object (list-of-records is awkward to bind).
   */
  private record ExtractionResult(List<ExtractedMemory> memories) {
  }

  /**
   * Structured-output target for one extracted candidate (extract / extractDetail). Shape v1.
   */
  private record RawCandidate(String content, String suggestedType) {
  }

  /**
   * Wrapper so Spring AI binds a top-level object for the extraction passes. Shape v1.
   */
  private record CandidateList(List<RawCandidate> candidates) {
  }

  /**
   * Structured-output target for {@link #verify}. Shape v1.
   */
  private record VerificationDto(String verdict, String content, String reason) {
  }

  /**
   * Structured-output target for {@link #classify}. Shape v1.
   */
  private record ClassificationDto(String type, String topicKey, List<String> interrogativeQueries, String payload) {
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
