package dev.alvo.pieria.api;

import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.ModelUnavailableException;

import java.util.List;

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
  public float[] embed(String text) {
    return new float[0];
  }
}
