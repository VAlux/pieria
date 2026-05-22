package dev.alvo.pieria.api;

import dev.alvo.pieria.domain.ContentId;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.Profile;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.storage.MemoryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal in-memory {@link MemoryStore} for API slice tests. Independent of the storage agent's
 * concrete implementation. Not thread-safe; sufficient for single-threaded MockMvc tests.
 */
class StubMemoryStore implements MemoryStore {

  private final Map<String, Profile> profilesByName = new LinkedHashMap<>();
  // profileId -> (memoryId -> memory)
  private final Map<String, Map<String, Memory>> memories = new LinkedHashMap<>();
  private final List<Message> messages = new ArrayList<>();

  @Override
  public Profile getOrCreateProfile(String name) {
    return profilesByName.computeIfAbsent(name, n -> {
      Profile p = new Profile("prof-" + n, n, Instant.now());
      memories.put(p.id(), new LinkedHashMap<>());
      return p;
    });
  }

  @Override
  public Optional<Profile> findProfile(String name) {
    return Optional.ofNullable(profilesByName.get(name));
  }

  @Override
  public void insertMessages(String profileId, String sessionId, List<Message> msgs) {
    messages.addAll(msgs);
  }

  @Override
  public Memory insertMemory(String profileId, Memory memory) {
    String id = memory.id() != null
      ? memory.id()
      : ContentId.forMemory(memory.sessionId(), memory.type(), memory.content());
    Instant createdAt = memory.createdAt() != null ? memory.createdAt() : Instant.now();
    Memory stored = new Memory(id, memory.sessionId(), memory.type(), memory.content(),
      memory.topicKey(), memory.supersedes(), memory.superseded(),
      memory.payload(), memory.embedText(), createdAt);
    memories.get(profileId).putIfAbsent(id, stored);
    return memories.get(profileId).get(id);
  }

  @Override
  public List<Memory> listMemories(String profileId, MemoryType typeFilter, String sessionFilter) {
    List<Memory> out = new ArrayList<>();
    for (Memory m : memories.getOrDefault(profileId, Map.of()).values()) {
      if (m.superseded()) {
        continue;
      }
      if (typeFilter != null && m.type() != typeFilter) {
        continue;
      }
      if (sessionFilter != null && !sessionFilter.equals(m.sessionId())) {
        continue;
      }
      out.add(m);
    }
    return out;
  }

  @Override
  public boolean forgetMemory(String profileId, String memoryId) {
    Map<String, Memory> profileMemories = memories.getOrDefault(profileId, Map.of());
    Memory existing = profileMemories.get(memoryId);
    if (existing == null || existing.superseded()) {
      return false;
    }
    profileMemories.put(memoryId, new Memory(existing.id(), existing.sessionId(),
      existing.type(), existing.content(), existing.topicKey(), existing.supersedes(),
      true, existing.payload(), existing.embedText(), existing.createdAt()));
    return true;
  }

  @Override
  public List<ExportRow> exportProfile(String profileId) {
    String profileName = profilesByName.values().stream()
      .filter(p -> p.id().equals(profileId))
      .map(Profile::name)
      .findFirst()
      .orElse("unknown");
    List<ExportRow> rows = new ArrayList<>();
    for (Memory m : memories.getOrDefault(profileId, Map.of()).values()) {
      rows.add(new ExportRow(profileName, m));
    }
    return rows;
  }

  @Override
  public List<RecallCandidate> findRecallCandidates(String profileId, String query, int limit) {
    List<RecallCandidate> out = new ArrayList<>();
    for (Memory m : listMemories(profileId, null, null)) {
      if (m.content() != null && m.content().toLowerCase().contains(query.toLowerCase())) {
        out.add(new RecallCandidate(m, 1.0, "like"));
      }
      if (out.size() >= limit) {
        break;
      }
    }
    return out;
  }
}
