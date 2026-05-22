package dev.alvo.pieria.model;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.Chunk;
import dev.alvo.pieria.domain.Classification;
import dev.alvo.pieria.domain.ExtractedCandidate;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.domain.VerificationResult;
import dev.alvo.pieria.domain.VerificationVerdict;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Spring AI / Ollama implementation of {@link ModelGateway}. Uses the small chat client for
 * Phase 1 naive extraction (structured output) and the large chat client for synthesis. Any
 * failure reaching the provider is wrapped in {@link ModelUnavailableException} with a generic
 * message so provider hosts/secrets never leak to callers.
 */
@Component
public class OllamaModelGateway implements ModelGateway {

  private final ChatClient extractionChatClient;
  private final ChatClient synthesisChatClient;
  private final EmbeddingModel embeddingModel;
  private final PieriaProperties properties;

  public OllamaModelGateway(@Qualifier("extractionChatClient") ChatClient extractionChatClient,
                            @Qualifier("synthesisChatClient") ChatClient synthesisChatClient,
                            EmbeddingModel embeddingModel,
                            PieriaProperties properties) {
    this.extractionChatClient = extractionChatClient;
    this.synthesisChatClient = synthesisChatClient;
    this.embeddingModel = embeddingModel;
    this.properties = properties;
  }

  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.strip();
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
      result = extractionChatClient.prompt()
        .user(prompt)
        .call()
        .entity(ExtractionResult.class);
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

  // --- Phase 2 pipeline stages (SPEC 6.2-6.4) ----------------------------------------------
  // All four stages below run on the small/fast extraction client (SPEC 4.1: structured stages).
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
      return an empty list.

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
      capturing, return an empty list.

      Chunk transcript:
      %s
      """.formatted(chunk.transcript());
    return callExtraction(prompt, chunk.index(), "extractDetail");
  }

  private List<ExtractedCandidate> callExtraction(String prompt, int chunkIndex, String stage) {
    CandidateList result;
    try {
      result = extractionChatClient.prompt()
        .user(prompt)
        .call()
        .entity(CandidateList.class);
    } catch (RuntimeException e) {
      throw new ModelUnavailableException("model " + stage + " failed", e);
    }
    if (result == null || result.candidates() == null) {
      return List.of();
    }
    List<ExtractedCandidate> candidates = new ArrayList<>();
    for (RawCandidate raw : result.candidates()) {
      if (raw == null || raw.content() == null || raw.content().isBlank()) {
        continue;
      }
      MemoryType suggested = parseTypeOrNull(raw.suggestedType());
      candidates.add(new ExtractedCandidate(raw.content().strip(), suggested, chunkIndex, stage));
    }
    return candidates;
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
      dto = extractionChatClient.prompt()
        .user(prompt)
        .call()
        .entity(VerificationDto.class);
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
        answers (interrogative search queries, SPEC 8.1)
      - payload: a JSON object string of extra structured fields, or "{}"

      Memory content:
      %s
      """.formatted(content);

    ClassificationDto dto;
    try {
      dto = extractionChatClient.prompt()
        .user(prompt)
        .call()
        .entity(ClassificationDto.class);
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
  public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
    List<RecallCandidate> safeCandidates = candidates == null ? List.of() : candidates;
    String context = safeCandidates.stream()
      .map(c -> "- " + c.memory().content())
      .collect(Collectors.joining("\n"));
    if (context.isBlank()) {
      context = "(no candidate memories were retrieved)";
    }

    String prompt = """
      You are answering a question using only the remembered memories below.
      If the memories do not contain the answer, say you don't know.
      
      Question:
      %s
      
      Remembered memories:
      %s
      """.formatted(query, context);

    try {
      return synthesisChatClient.prompt()
        .user(prompt)
        .call()
        .content();
    } catch (RuntimeException e) {
      throw new ModelUnavailableException("model synthesis failed", e);
    }
  }

  @Override
  public float[] embed(String text) {
    try {
      return embeddingModel.embed(text);
    } catch (RuntimeException e) {
      throw new ModelUnavailableException("model embedding failed", e);
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
  private record ClassificationDto(String type, String topicKey,
                                   List<String> interrogativeQueries, String payload) {
  }
}
