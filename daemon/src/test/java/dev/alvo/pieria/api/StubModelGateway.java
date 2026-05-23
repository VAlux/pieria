package dev.alvo.pieria.api;

import dev.alvo.pieria.domain.Chunk;
import dev.alvo.pieria.domain.Classification;
import dev.alvo.pieria.domain.ExtractedCandidate;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.QueryAnalysis;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.domain.VerificationResult;
import dev.alvo.pieria.domain.VerificationVerdict;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.ModelUnavailableException;

import java.util.List;
import java.util.Locale;

/**
 * Stub {@link ModelGateway} for API slice tests. Independent of the model agent's impl. Extraction
 * turns each user message into one FACT; synthesis joins candidate contents. {@code unavailable}
 * forces a 503 path.
 */
class StubModelGateway implements ModelGateway {

  private boolean unavailable;

  void setUnavailable(boolean unavailable) {
    this.unavailable = unavailable;
  }

  @Override
  public List<Memory> extractMemories(List<Message> messages) {
    if (unavailable) {
      throw new ModelUnavailableException("model down");
    }
    return messages.stream()
      .filter(m -> "user".equalsIgnoreCase(m.role()))
      .map(m -> Memory.of(MemoryType.FACT, m.content(), m.sessionId(), null, null))
      .toList();
  }

  @Override
  public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
    if (unavailable) {
      throw new ModelUnavailableException("model down");
    }
    if (candidates.isEmpty()) {
      return "No relevant memories.";
    }
    StringBuilder sb = new StringBuilder("Based on memory: ");
    for (RecallCandidate c : candidates) {
      sb.append(c.memory().content()).append("; ");
    }
    return sb.toString().trim();
  }

  @Override
  public List<ExtractedCandidate> extract(Chunk chunk) {
    if (unavailable) {
      throw new ModelUnavailableException("model down");
    }
    if (chunk == null || chunk.transcript() == null || chunk.transcript().isBlank()) {
      return List.of();
    }
    return List.of(new ExtractedCandidate(
      "chunk:" + chunk.index() + ":" + chunk.transcript(), MemoryType.FACT, chunk.index(), "extract"));
  }

  @Override
  public List<ExtractedCandidate> extractDetail(Chunk chunk) {
    if (unavailable) {
      throw new ModelUnavailableException("model down");
    }
    if (chunk == null || chunk.transcript() == null || chunk.transcript().isBlank()) {
      return List.of();
    }
    return List.of(new ExtractedCandidate(
      "chunk:" + chunk.index() + ":" + chunk.transcript() + " [detail]",
      MemoryType.FACT, chunk.index(), "extractDetail"));
  }

  @Override
  public VerificationResult verify(ExtractedCandidate candidate, String transcript) {
    if (unavailable) {
      throw new ModelUnavailableException("model down");
    }
    if (candidate == null || candidate.content() == null || candidate.content().isBlank()) {
      return new VerificationResult(VerificationVerdict.DROP, "", "empty candidate");
    }
    if (candidate.content().toUpperCase(Locale.ROOT).contains("UNSUPPORTED")) {
      return new VerificationResult(VerificationVerdict.DROP, "", "unsupported by transcript");
    }
    return new VerificationResult(VerificationVerdict.PASS, candidate.content(), "supported");
  }

  @Override
  public Classification classify(String content) {
    if (unavailable) {
      throw new ModelUnavailableException("model down");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be blank");
    }
    String firstWord = content.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT)
      .replaceAll("[^a-z0-9]", "");
    return new Classification(MemoryType.FACT, "topic." + firstWord,
      List.of("what is " + firstWord + "?", "tell me about " + firstWord, "details on " + firstWord),
      "{}");
  }

  @Override
  public QueryAnalysis analyzeQuery(String query) {
    if (unavailable) {
      throw new ModelUnavailableException("model down");
    }
    if (query == null || query.isBlank()) {
      return new QueryAnalysis(List.of(), List.of(), null);
    }
    List<String> terms = new java.util.ArrayList<>();
    for (String token : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
      if (!token.isBlank() && !terms.contains(token)) {
        terms.add(token);
      }
    }
    List<String> topicKeys = terms.isEmpty() ? List.of() : List.of("topic." + terms.get(0));
    return new QueryAnalysis(topicKeys, terms, "answer: " + query.strip());
  }

  @Override
  public float[] embed(String text) {
    return new float[0];
  }
}
