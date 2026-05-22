package dev.alvo.pieria.model;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.RecallCandidate;
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
}
