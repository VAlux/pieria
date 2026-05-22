package dev.alvo.pieria.model;

import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.RecallCandidate;

import java.util.List;

/**
 * Deterministic, network-free {@link ModelGateway} test double, shared across module test suites
 * (model + api). All methods produce predictable output derived from their inputs so tests can
 * assert exact values. Set {@link #setUnavailable(boolean)} to make every call raise
 * {@link ModelUnavailableException} (for verifying 503 mapping).
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
  public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
    failIfUnavailable();
    int count = candidates == null ? 0 : candidates.size();
    return "Answer to '" + query + "' from " + count + " candidate(s).";
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
