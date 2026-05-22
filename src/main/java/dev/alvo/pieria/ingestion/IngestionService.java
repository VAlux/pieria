package dev.alvo.pieria.ingestion;

import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.Profile;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists raw conversation messages first,
 * then runs naive model extraction and stores each accepted candidate. Explicit single-memory
 * writes ({@link #remember}) bypass the model entirely.
 */
@Service
public class IngestionService {

  private final MemoryStore store;
  private final ModelGateway modelGateway;

  public IngestionService(MemoryStore store, ModelGateway modelGateway) {
    this.store = store;
    this.modelGateway = modelGateway;
  }

  private static Memory getMemory(String sessionId, Memory candidate) {
    Memory toStore;
    if (candidate.sessionId() == null) {
      toStore = new Memory(
        candidate.id(),
        sessionId,
        candidate.type(),
        candidate.content(),
        candidate.topicKey(),
        candidate.supersedes(),
        candidate.superseded(),
        candidate.payload(),
        candidate.embedText(),
        candidate.createdAt());
    } else {
      toStore = candidate;
    }

    return toStore;
  }

  /**
   * Ingest a conversation: store raw messages, extract candidate memories via the model, and
   * persist each one. Returns the stored memories (with assigned ids/timestamps).
   */
  public List<Memory> ingest(String profileName, String sessionId, List<Message> messages) {
    Profile profile = store.getOrCreateProfile(profileName);

    List<Message> sessionMessages = new ArrayList<>(messages.size());
    for (Message message : messages) {
      sessionMessages.add(new Message(
        message.id(),
        message.sessionId() == null ? sessionId : message.sessionId(),
        message.role(),
        message.content(),
        message.createdAt()));
    }

    // Store raw messages first so ingest is idempotent and inspectable.
    store.insertMessages(profile.id(), sessionId, sessionMessages);

    List<Memory> extracted = modelGateway.extractMemories(sessionMessages);
    List<Memory> stored = new ArrayList<>(extracted.size());

    for (Memory candidate : extracted) {
      Memory toStore = getMemory(sessionId, candidate);
      stored.add(store.insertMemory(profile.id(), toStore));
    }
    return stored;
  }

  /**
   * Explicit single-memory write (POST /memories). No model call: persist the memory directly
   * under the resolved profile and return the stored row.
   */
  public Memory remember(String profileName, Memory memory) {
    Profile profile = store.getOrCreateProfile(profileName);
    return store.insertMemory(profile.id(), memory);
  }
}
