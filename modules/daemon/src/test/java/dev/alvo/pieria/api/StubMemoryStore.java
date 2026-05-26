package dev.alvo.pieria.api;

import dev.alvo.pieria.domain.ContentId;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.OutboxEntry;
import dev.alvo.pieria.domain.Profile;
import dev.alvo.pieria.domain.ProfileCount;
import dev.alvo.pieria.domain.ProfileStats;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.storage.MemoryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Minimal in-memory {@link MemoryStore} for API slice tests. Independent of the storage agent's
 * concrete implementation. Not thread-safe; sufficient for single-threaded MockMvc tests.
 */
class StubMemoryStore implements MemoryStore {

  private final Map<String, Profile> profilesByName = new LinkedHashMap<>();
  // profileId -> (memoryId -> memory)
  private final Map<String, Map<String, Memory>> memories = new LinkedHashMap<>();
  private final List<Message> messages = new ArrayList<>();
  // memoryId -> outbox entry (insertion order approximates enqueue order)
  private final Map<String, OutboxEntry> outbox = new LinkedHashMap<>();
  private final Map<String, Long> outboxEnqueuedAt = new LinkedHashMap<>();
  // memoryId -> embedding written by completeVectorization
  private final Map<String, float[]> embeddings = new LinkedHashMap<>();
  private long enqueueSeq = 0;

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
  public List<ProfileCount> listProfiles() {
    return profilesByName.values().stream()
      .sorted(Comparator.comparing(Profile::name))
      .map(p -> new ProfileCount(p, listMemories(p.id(), null, null).size()))
      .toList();
  }

  @Override
  public ProfileStats profileStats(String profileId) {
    Map<String, Long> byType = new LinkedHashMap<>();
    for (MemoryType t : MemoryType.values()) {
      byType.put(t.wire(), 0L);
    }
    long superseded = 0;
    java.util.Set<String> sessions = new java.util.LinkedHashSet<>();
    Instant first = null;
    Instant last = null;
    for (Memory m : memories.getOrDefault(profileId, Map.of()).values()) {
      if (m.superseded()) {
        superseded++;
        continue;
      }
      byType.merge(m.type().wire(), 1L, Long::sum);
      if (m.sessionId() != null) {
        sessions.add(m.sessionId());
      }
      if (m.createdAt() != null) {
        if (first == null || m.createdAt().isBefore(first)) {
          first = m.createdAt();
        }
        if (last == null || m.createdAt().isAfter(last)) {
          last = m.createdAt();
        }
      }
    }
    long total = byType.values().stream().mapToLong(Long::longValue).sum();
    return new ProfileStats(total, byType, superseded, sessions.size(), first, last);
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
  public StoreOutcome store(String profileId, Memory memory) {
    String supersededId = null;

    String id = memory.id() != null
      ? memory.id()
      : ContentId.forMemory(memory.sessionId(), memory.type(), memory.content());

    boolean keyed = (memory.type() == MemoryType.FACT || memory.type() == MemoryType.INSTRUCTION)
      && memory.topicKey() != null;
    if (keyed) {
      Map<String, Memory> profileMemories = memories.computeIfAbsent(profileId, k -> new LinkedHashMap<>());
      Memory active = profileMemories.values().stream()
        .filter(m -> !m.superseded() && m.type() == memory.type()
          && memory.topicKey().equals(m.topicKey()))
        .reduce((first, second) -> second) // last in insertion order
        .orElse(null);
      // Skip when the active row IS the incoming memory (same id): keep re-ingest idempotent.
      if (active != null && !active.id().equals(id)) {
        supersededId = active.id();
        profileMemories.put(active.id(), new Memory(active.id(), active.sessionId(),
          active.type(), active.content(), active.topicKey(), active.supersedes(),
          true, active.payload(), active.embedText(), active.createdAt()));
        outbox.remove(active.id());
        outboxEnqueuedAt.remove(active.id());
        embeddings.remove(active.id());
      }
    }

    Memory toInsert = new Memory(id, memory.sessionId(), memory.type(), memory.content(),
      memory.topicKey(), supersededId != null ? supersededId : memory.supersedes(),
      memory.superseded(), memory.payload(), memory.embedText(), memory.createdAt());
    Memory stored = insertMemory(profileId, toInsert);

    boolean enqueuedVector = false;
    if (memory.type() != MemoryType.TASK && !outbox.containsKey(stored.id())) {
      outbox.put(stored.id(), new OutboxEntry(stored.id(), 0));
      outboxEnqueuedAt.put(stored.id(), enqueueSeq++);
      enqueuedVector = true;
    }

    return new StoreOutcome(stored, supersededId, enqueuedVector);
  }

  @Override
  public List<Memory> findActiveByTopicKey(String profileId, MemoryType type, String topicKey) {
    if (topicKey == null) {
      return List.of();
    }
    List<Memory> out = new ArrayList<>();
    for (Memory m : memories.getOrDefault(profileId, Map.of()).values()) {
      if (!m.superseded() && m.type() == type && topicKey.equals(m.topicKey())) {
        out.add(m);
      }
    }
    return out;
  }

  @Override
  public Optional<Memory> findMemoryById(String memoryId) {
    for (Map<String, Memory> profileMemories : memories.values()) {
      Memory m = profileMemories.get(memoryId);
      if (m != null) {
        return Optional.of(m);
      }
    }
    return Optional.empty();
  }

  @Override
  public List<OutboxEntry> drainOutbox(int batchSize) {
    if (batchSize <= 0) {
      return List.of();
    }
    return outbox.values().stream()
      .sorted(Comparator.comparingLong(e -> outboxEnqueuedAt.getOrDefault(e.memoryId(), 0L)))
      .limit(batchSize)
      .toList();
  }

  @Override
  public void recordOutboxFailure(String memoryId, String lastError) {
    OutboxEntry existing = outbox.get(memoryId);
    if (existing != null) {
      outbox.put(memoryId, new OutboxEntry(memoryId, existing.attempts() + 1));
    }
  }

  @Override
  public void deleteOutboxRow(String memoryId) {
    outbox.remove(memoryId);
    outboxEnqueuedAt.remove(memoryId);
  }

  @Override
  public void completeVectorization(String memoryId, float[] embedding) {
    embeddings.put(memoryId, embedding);
    outbox.remove(memoryId);
    outboxEnqueuedAt.remove(memoryId);
  }

  @Override
  public OptionalLong vectorizationOutboxDepth() {
    return OptionalLong.of(outbox.size());
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

  // --- Retrieval channels: simple in-memory lexical/keyed matching (no real FTS/vector) ---

  private static List<String> terms(String raw) {
    List<String> out = new ArrayList<>();
    if (raw == null) {
      return out;
    }
    for (String t : raw.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
      if (!t.isBlank() && !out.contains(t)) {
        out.add(t);
      }
    }
    return out;
  }

  @Override
  public boolean isVectorSearchAvailable() {
    return false;
  }

  @Override
  public List<Memory> searchMemoriesFts(String profileId, String matchQuery, int limit) {
    List<String> terms = terms(matchQuery);
    if (terms.isEmpty() || limit <= 0) {
      return List.of();
    }
    List<Memory> matched = new ArrayList<>();
    for (Memory m : listMemories(profileId, null, null)) {
      String content = m.content() == null ? "" : m.content().toLowerCase(java.util.Locale.ROOT);
      if (terms.stream().anyMatch(content::contains)) {
        matched.add(m);
      }
    }
    return matched.size() > limit ? matched.subList(0, limit) : matched;
  }

  @Override
  public List<Memory> searchMemoriesByMessageFts(String profileId, String matchQuery, int limit) {
    List<String> terms = terms(matchQuery);
    if (terms.isEmpty() || limit <= 0) {
      return List.of();
    }
    java.util.Set<String> matchedSessions = new java.util.LinkedHashSet<>();
    for (Message msg : messages) {
      String content = msg.content() == null ? "" : msg.content().toLowerCase(java.util.Locale.ROOT);
      if (terms.stream().anyMatch(content::contains)) {
        matchedSessions.add(msg.sessionId());
      }
    }
    List<Memory> out = new ArrayList<>();
    for (Memory m : listMemories(profileId, null, null)) {
      if (m.sessionId() != null && matchedSessions.contains(m.sessionId())) {
        out.add(m);
      }
      if (out.size() >= limit) {
        break;
      }
    }
    return out;
  }

  @Override
  public List<Memory> exactKeyLookup(String profileId, List<String> topicKeys, int limit) {
    if (topicKeys == null || topicKeys.isEmpty() || limit <= 0) {
      return List.of();
    }
    List<Memory> out = new ArrayList<>();
    for (String key : topicKeys) {
      for (Memory m : listMemories(profileId, null, null)) {
        if (key.equals(m.topicKey())
            && (m.type() == MemoryType.FACT || m.type() == MemoryType.INSTRUCTION)
            && !out.contains(m)) {
          out.add(m);
        }
        if (out.size() >= limit) {
          return out;
        }
      }
    }
    return out;
  }

  @Override
  public List<Memory> vectorSearch(String profileId, float[] queryEmbedding, int limit) {
    return List.of(); // stub has no vector index
  }
}
