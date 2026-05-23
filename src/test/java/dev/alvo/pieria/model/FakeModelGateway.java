package dev.alvo.pieria.model;

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

import java.util.List;
import java.util.Locale;

/**
 * Deterministic, network-free {@link ModelGateway} test double, shared across module test suites
 * (model + api). All methods produce predictable output derived from their inputs so tests can
 * assert exact values. Set {@link #setUnavailable(boolean)} to make every call raise
 * {@link ModelUnavailableException} (for verifying 503 mapping).
 *
 * <h2>Content sentinels (case-insensitive substrings) that drive deterministic behavior</h2>
 * Integration / service tests can embed these substrings in candidate content to force a path:
 * <ul>
 *   <li>{@code UNSUPPORTED} — {@link #verify} returns {@link VerificationVerdict#DROP}.</li>
 *   <li>{@code TYPO} — {@link #verify} returns {@link VerificationVerdict#CORRECT} with content
 *       {@code "corrected: " + originalContent}; otherwise verify returns
 *       {@link VerificationVerdict#PASS} echoing the original content.</li>
 *   <li>{@code EVENT} — {@link #classify} assigns {@link MemoryType#EVENT} (topicKey {@code null}).</li>
 *   <li>{@code INSTRUCTION} — {@link #classify} assigns {@link MemoryType#INSTRUCTION}.</li>
 *   <li>{@code TASK} — {@link #classify} assigns {@link MemoryType#TASK} (topicKey {@code null}).</li>
 *   <li>otherwise {@link #classify} assigns {@link MemoryType#FACT}.</li>
 * </ul>
 * For keyed types (FACT, INSTRUCTION) the topicKey is {@code "topic." + firstWordLowercased}.
 * {@link #extract} yields one candidate per chunk (content {@code "chunk:<index>:" + transcript});
 * {@link #extractDetail} yields one candidate suffixed with {@code " [detail]"}.
 * {@link #classify} always returns 3 interrogative queries.
 * {@link #analyzeQuery} lowercases + splits the query on non-alphanumerics into terms; it returns
 * those terms as {@code ftsTerms}, a single topicKey {@code "topic." + firstTerm} (empty list if no
 * terms), and a {@code hydeStatement} of {@code "answer: " + query.strip()} (or {@code null} for a
 * blank/null query). Honors {@link #setUnavailable(boolean)}.
 */
public class FakeModelGateway implements ModelGateway {

  /**
   * Fixed embedding width returned by {@link #embed(String)}.
   */
  public static final int EMBEDDING_DIMENSION = 1024;

  private boolean unavailable;

  /**
   * When {@code true}, every method throws {@link ModelUnavailableException}.
   */
  public void setUnavailable(boolean unavailable) {
    this.unavailable = unavailable;
  }

  private void failIfUnavailable() {
    if (unavailable) {
      throw new ModelUnavailableException("fake model is unavailable");
    }
  }

  @Override
  public List<Memory> extractMemories(List<Message> messages) {
    failIfUnavailable();
    if (messages == null || messages.isEmpty()) {
      return List.of();
    }
    // Echo the last user message (or the last message) as a single FACT.
    Message chosen = null;
    for (Message m : messages) {
      if ("user".equalsIgnoreCase(m.role())) {
        chosen = m;
      }
    }
    if (chosen == null) {
      chosen = messages.get(messages.size() - 1);
    }
    String sessionId = messages.get(0).sessionId();
    Memory memory = Memory.of(MemoryType.FACT, chosen.content(), sessionId, null, "{}");
    return List.of(memory);
  }

  @Override
  public List<ExtractedCandidate> extract(Chunk chunk) {
    failIfUnavailable();
    if (chunk == null || chunk.transcript() == null || chunk.transcript().isBlank()) {
      return List.of();
    }
    String content = "chunk:" + chunk.index() + ":" + chunk.transcript();
    return List.of(new ExtractedCandidate(content, MemoryType.FACT, chunk.index(), "extract"));
  }

  @Override
  public List<ExtractedCandidate> extractDetail(Chunk chunk) {
    failIfUnavailable();
    if (chunk == null || chunk.transcript() == null || chunk.transcript().isBlank()) {
      return List.of();
    }
    String content = "chunk:" + chunk.index() + ":" + chunk.transcript() + " [detail]";
    return List.of(new ExtractedCandidate(content, MemoryType.FACT, chunk.index(), "extractDetail"));
  }

  @Override
  public VerificationResult verify(ExtractedCandidate candidate, String transcript) {
    failIfUnavailable();
    if (candidate == null || candidate.content() == null || candidate.content().isBlank()) {
      return new VerificationResult(VerificationVerdict.DROP, "", "empty candidate");
    }
    String content = candidate.content();
    String upper = content.toUpperCase(Locale.ROOT);
    if (upper.contains("UNSUPPORTED")) {
      return new VerificationResult(VerificationVerdict.DROP, "", "unsupported by transcript");
    }
    if (upper.contains("TYPO")) {
      return new VerificationResult(VerificationVerdict.CORRECT, "corrected: " + content, "fixed typo");
    }
    return new VerificationResult(VerificationVerdict.PASS, content, "supported");
  }

  @Override
  public Classification classify(String content) {
    failIfUnavailable();
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be blank");
    }
    String upper = content.toUpperCase(Locale.ROOT);
    MemoryType type;
    if (upper.contains("EVENT")) {
      type = MemoryType.EVENT;
    } else if (upper.contains("INSTRUCTION")) {
      type = MemoryType.INSTRUCTION;
    } else if (upper.contains("TASK")) {
      type = MemoryType.TASK;
    } else {
      type = MemoryType.FACT;
    }
    String firstWord = content.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT)
      .replaceAll("[^a-z0-9]", "");
    String topicKey = (type == MemoryType.FACT || type == MemoryType.INSTRUCTION)
      ? "topic." + firstWord
      : null;
    List<String> queries = List.of(
      "what is " + firstWord + "?",
      "tell me about " + firstWord,
      "details on " + firstWord);
    return new Classification(type, topicKey, queries, "{}");
  }

  @Override
  public QueryAnalysis analyzeQuery(String query) {
    failIfUnavailable();
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
  public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
    failIfUnavailable();
    int count = candidates == null ? 0 : candidates.size();
    return "Answer to '" + query + "' from " + count + " candidate(s).";
  }

  /**
   * Surfaces the temporal-fact count so retrieval-pipeline tests can assert that deterministic
   * temporal facts reached synthesis. The candidate contents are echoed so callers can also assert
   * which fused memories were passed in.
   */
  @Override
  public String synthesizeRecall(String query, List<RecallCandidate> candidates,
                                 List<dev.alvo.pieria.domain.TemporalFact> temporalFacts) {
    failIfUnavailable();
    int count = candidates == null ? 0 : candidates.size();
    int temporal = temporalFacts == null ? 0 : temporalFacts.size();
    String contents = candidates == null ? "" : candidates.stream()
      .map(c -> c.memory().content())
      .collect(java.util.stream.Collectors.joining("; "));
    return "Answer to '" + query + "' from " + count + " candidate(s), "
      + temporal + " temporal fact(s): " + contents;
  }

  @Override
  public float[] embed(String text) {
    failIfUnavailable();
    float[] vector = new float[EMBEDDING_DIMENSION];
    int hash = text == null ? 0 : text.hashCode();
    for (int i = 0; i < vector.length; i++) {
      // Deterministic, bounded pattern derived from the text hash and index.
      vector[i] = ((hash + i) % 7) / 7.0f;
    }
    return vector;
  }
}
