package dev.alvo.pieria.api;

import dev.alvo.pieria.domain.ContentId;
import dev.alvo.pieria.domain.graph.Edge;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.graph.GraphCounts;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.graph.IncidentEdge;
import dev.alvo.pieria.domain.graph.NeighborHop;
import dev.alvo.pieria.domain.graph.RankedEntity;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.ingestion.model.OutboxEntry;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.domain.profile.ProfileCount;
import dev.alvo.pieria.domain.profile.ProfileStats;
import dev.alvo.pieria.model.usage.InferenceTier;
import dev.alvo.pieria.model.usage.TierUsage;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.storage.MemoryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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
  // profileId -> per-tier inference spend
  private final Map<String, Map<InferenceTier, TierUsage>> inferenceUsage = new LinkedHashMap<>();
  private long enqueueSeq = 0;

  @Override
  public void recordInferenceUsage(String profileId, Map<InferenceTier, TierUsage> usage) {
    Map<InferenceTier, TierUsage> existing =
      inferenceUsage.computeIfAbsent(profileId, k -> new EnumMap<>(InferenceTier.class));
    usage.forEach((tier, u) -> existing.merge(tier, u, (a, b) -> new TierUsage(
      a.calls() + b.calls(), a.promptTokens() + b.promptTokens(), a.completionTokens() + b.completionTokens())));
  }

  @Override
  public Map<InferenceTier, TierUsage> inferenceUsage(String profileId) {
    return inferenceUsage.getOrDefault(profileId, Map.of());
  }

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
  public void deleteProfile(String profileId) {
    profilesByName.values().removeIf(p -> p.id().equals(profileId));
    memories.remove(profileId);
    entities.remove(profileId);
    edges.remove(profileId);
    inferenceUsage.remove(profileId);
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
      : ContentId.forMemory(profileId, memory.sessionId(), memory.type(), memory.content());
    Instant createdAt = memory.createdAt() != null ? memory.createdAt() : Instant.now();
    Memory stored = new Memory(id, memory.sessionId(), memory.type(), memory.content(),
      memory.topicKey(), memory.supersedes(), memory.superseded(),
      memory.payload(), memory.embedText(), createdAt);
    memories.get(profileId).putIfAbsent(id, stored);
    return memories.get(profileId).get(id);
  }

  @Override
  public StoreOutcome store(String profileId, Memory memory, GraphFragment graph) {
    return store(profileId, memory, graph, dev.alvo.pieria.tools.Tokens.estimate(memory.content()));
  }

  // This stub does not model provenance/source tokens separately; sourceTokens is accepted for
  // interface conformance but not persisted (API slice tests don't assert on it).
  @Override
  public StoreOutcome store(String profileId, Memory memory, GraphFragment graph, long sourceTokens) {
    String supersededId = null;

    String id = memory.id() != null
      ? memory.id()
      : ContentId.forMemory(profileId, memory.sessionId(), memory.type(), memory.content());

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
    boolean inserted = !memories.computeIfAbsent(profileId, _ -> new LinkedHashMap<>()).containsKey(id);
    Memory stored = insertMemory(profileId, toInsert);

    boolean enqueuedVector = false;
    if (memory.type() != MemoryType.TASK && !outbox.containsKey(stored.id())) {
      outbox.put(stored.id(), new OutboxEntry(stored.id(), 0));
      outboxEnqueuedAt.put(stored.id(), enqueueSeq++);
      enqueuedVector = true;
    }

    return new StoreOutcome(stored, supersededId, enqueuedVector, inserted);
  }

  // Graph surface: empty so the second-wave graph channel runs cleanly (0 hits) in API slice tests.
  @Override
  public List<Entity> findEntitiesByName(String profileId, List<String> names, int limit) {
    return List.of();
  }

  @Override
  public List<Entity> entitiesForMemories(String profileId, List<String> memoryIds, int limit) {
    return List.of();
  }

  @Override
  public List<String> neighborhood(String profileId, List<String> seedEntityIds, int depth, int fanout) {
    return List.of();
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
  public List<Memory> listMemories(String profileId, MemoryType typeFilter, String sessionFilter,
                                   boolean includeSuperseded) {
    List<Memory> out = new ArrayList<>();
    for (Memory m : memories.getOrDefault(profileId, Map.of()).values()) {
      if (m.superseded() && !includeSuperseded) {
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

  // ---- entity-relation graph (enough for the /graph endpoint slice test) ----

  // profileId -> (entityId -> entity)
  private final Map<String, Map<String, Entity>> entities = new LinkedHashMap<>();
  // profileId -> edges
  private final Map<String, List<Edge>> edges = new LinkedHashMap<>();

  @Override
  public Entity upsertEntity(String profileId, Entity entity) {
    String id = entity.id() != null ? entity.id()
      : ContentId.forEntity(profileId, entity.type(), entity.name());
    Entity stored = new Entity(id, profileId, entity.type(), entity.name(),
      entity.payload() == null ? "{}" : entity.payload(),
      entity.createdAt() == null ? Instant.now() : entity.createdAt());
    entities.computeIfAbsent(profileId, k -> new LinkedHashMap<>()).putIfAbsent(id, stored);
    return entities.get(profileId).get(id);
  }

  @Override
  public Edge upsertEdge(String profileId, Edge edge) {
    String id = edge.id() != null ? edge.id()
      : ContentId.forEdge(profileId, edge.sourceEntityId(), edge.relation(), edge.targetEntityId(), edge.memoryId());
    Edge stored = new Edge(id, profileId, edge.sourceEntityId(), edge.targetEntityId(),
      edge.relation(), edge.memoryId(), edge.createdAt() == null ? Instant.now() : edge.createdAt());
    // Insert-or-ignore on the content-addressed id, mirroring the real store: re-upserting the same
    // edge must not duplicate it.
    List<Edge> profileEdges = edges.computeIfAbsent(profileId, k -> new ArrayList<>());
    if (profileEdges.stream().noneMatch(e -> e.id().equals(id))) {
      profileEdges.add(stored);
    }
    return stored;
  }

  /** Edges whose provenance memory is still active — the predicate every graph read shares. */
  private List<Edge> activeEdges(String profileId) {
    Map<String, Memory> mem = memories.getOrDefault(profileId, Map.of());
    return edges.getOrDefault(profileId, List.of()).stream()
      .filter(e -> {
        Memory m = mem.get(e.memoryId());
        return m != null && !m.superseded();
      })
      .toList();
  }

  /** Active-edge degree for every entity the profile connects. */
  private Map<String, Integer> allDegrees(String profileId) {
    Map<String, Integer> degrees = new LinkedHashMap<>();
    for (Edge e : activeEdges(profileId)) {
      degrees.merge(e.sourceEntityId(), 1, Integer::sum);
      degrees.merge(e.targetEntityId(), 1, Integer::sum);
    }
    return degrees;
  }

  @Override
  public GraphCounts graphCounts(String profileId) {
    List<Edge> active = activeEdges(profileId);
    Map<String, Entity> ents = entities.getOrDefault(profileId, Map.of());
    long connected = allDegrees(profileId).keySet().stream().filter(ents::containsKey).count();
    return new GraphCounts((int) connected, active.size());
  }

  @Override
  public Map<String, Integer> entityTypeCounts(String profileId) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    entities.getOrDefault(profileId, Map.of()).values()
      .forEach(e -> counts.merge(e.type(), 1, Integer::sum));
    return counts;
  }

  @Override
  public List<RankedEntity> topEntitiesByDegree(String profileId, List<String> types, int limit) {
    Map<String, Entity> ents = entities.getOrDefault(profileId, Map.of());
    return allDegrees(profileId).entrySet().stream()
      .filter(e -> ents.containsKey(e.getKey()))
      .filter(e -> types == null || types.isEmpty() || types.contains(ents.get(e.getKey()).type()))
      .map(e -> new RankedEntity(ents.get(e.getKey()), e.getValue()))
      .sorted(Comparator.comparingInt(RankedEntity::degree).reversed()
        .thenComparing(r -> r.entity().name()))
      .limit(Math.max(0, limit))
      .toList();
  }

  @Override
  public List<RankedEntity> searchEntities(String profileId, String query, List<String> types, int limit) {
    if (query == null || query.isBlank() || limit <= 0) {
      return List.of();
    }
    String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
    Map<String, Integer> degrees = allDegrees(profileId);
    return entities.getOrDefault(profileId, Map.of()).values().stream()
      .filter(e -> e.name() != null && e.name().toLowerCase(java.util.Locale.ROOT).contains(needle))
      .filter(e -> types == null || types.isEmpty() || types.contains(e.type()))
      .map(e -> new RankedEntity(e, degrees.getOrDefault(e.id(), 0)))
      .sorted(Comparator.comparingInt(RankedEntity::degree).reversed()
        .thenComparing(r -> r.entity().name()))
      .limit(limit)
      .toList();
  }

  @Override
  public List<NeighborHop> graphNeighborhood(String profileId, String seedEntityId, int depth,
                                             List<String> types, int fanout) {
    if (seedEntityId == null || seedEntityId.isBlank()) {
      return List.of();
    }
    Map<String, Entity> ents = entities.getOrDefault(profileId, Map.of());
    List<Edge> active = activeEdges(profileId);

    List<NeighborHop> out = new ArrayList<>();
    java.util.LinkedHashSet<String> visited = new java.util.LinkedHashSet<>();
    visited.add(seedEntityId);
    out.add(new NeighborHop(seedEntityId, 0));

    List<String> frontier = List.of(seedEntityId);
    for (int hop = 1; hop <= Math.max(0, depth) && !frontier.isEmpty(); hop++) {
      List<String> next = new ArrayList<>();
      int budget = fanout;
      for (Edge e : active) {
        String neighbor = frontier.contains(e.sourceEntityId()) ? e.targetEntityId()
          : frontier.contains(e.targetEntityId()) ? e.sourceEntityId()
          : null;
        if (neighbor == null || budget <= 0) {
          continue;
        }
        Entity other = ents.get(neighbor);
        if (types != null && !types.isEmpty() && (other == null || !types.contains(other.type()))) {
          continue;
        }
        if (visited.add(neighbor)) {
          next.add(neighbor);
          out.add(new NeighborHop(neighbor, hop));
          budget--;
        }
      }
      frontier = next;
    }
    return List.copyOf(out);
  }

  @Override
  public List<Edge> inducedEdges(String profileId, List<String> entityIds) {
    if (entityIds == null || entityIds.isEmpty()) {
      return List.of();
    }
    return activeEdges(profileId).stream()
      .filter(e -> entityIds.contains(e.sourceEntityId()) && entityIds.contains(e.targetEntityId()))
      .toList();
  }

  @Override
  public List<Entity> findEntitiesByIds(String profileId, List<String> entityIds) {
    if (entityIds == null || entityIds.isEmpty()) {
      return List.of();
    }
    Map<String, Entity> ents = entities.getOrDefault(profileId, Map.of());
    return entityIds.stream().map(ents::get).filter(java.util.Objects::nonNull).toList();
  }

  @Override
  public Map<String, Integer> entityDegrees(String profileId, List<String> entityIds) {
    if (entityIds == null || entityIds.isEmpty()) {
      return Map.of();
    }
    Map<String, Integer> all = allDegrees(profileId);
    Map<String, Integer> out = new LinkedHashMap<>();
    entityIds.forEach(id -> {
      Integer deg = all.get(id);
      if (deg != null) {
        out.put(id, deg);
      }
    });
    return out;
  }

  @Override
  public List<IncidentEdge> incidentEdges(String profileId, String entityId, int limit) {
    if (entityId == null || entityId.isBlank() || limit <= 0) {
      return List.of();
    }
    Map<String, Entity> ents = entities.getOrDefault(profileId, Map.of());
    List<IncidentEdge> out = new ArrayList<>();
    for (Edge e : activeEdges(profileId)) {
      boolean outgoing = entityId.equals(e.sourceEntityId());
      boolean incoming = entityId.equals(e.targetEntityId());
      if (!outgoing && !incoming) {
        continue;
      }
      Entity other = ents.get(outgoing ? e.targetEntityId() : e.sourceEntityId());
      if (other != null) {
        out.add(new IncidentEdge(e, other, outgoing));
      }
      if (out.size() >= limit) {
        break;
      }
    }
    return out;
  }

  @Override
  public List<Memory> findMemoriesByEntities(String profileId, List<String> entityIds, int limit) {
    if (entityIds == null || entityIds.isEmpty() || limit <= 0) {
      return List.of();
    }
    Map<String, Memory> mem = memories.getOrDefault(profileId, Map.of());
    java.util.LinkedHashSet<Memory> out = new java.util.LinkedHashSet<>();
    for (Edge e : activeEdges(profileId)) {
      if (entityIds.contains(e.sourceEntityId()) || entityIds.contains(e.targetEntityId())) {
        Memory m = mem.get(e.memoryId());
        if (m != null) {
          out.add(m);
        }
      }
      if (out.size() >= limit) {
        break;
      }
    }
    return List.copyOf(out);
  }
}
