package dev.alvo.pieria.retrieval.channel;

import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.retrieval.model.QueryAnalysis;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.retrieval.RetrievalContext;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.storage.MemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GraphChannel}: seeding from query entities and wave-1 candidates, bounded
 * neighborhood expansion, and ranked candidate output. Uses a recording {@link MemoryStore} fake so
 * the seed set and traversal arguments can be asserted directly.
 */
class GraphChannelTests {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static Memory mem(String id, String content) {
    return new Memory(id, "s1", MemoryType.FACT, content, null, null, false, "{}", null, T0);
  }

  private static RetrievalContext ctx(List<String> queryEntities, List<RetrievalCandidate> seeds) {
    QueryAnalysis analysis = new QueryAnalysis(List.of(), List.of(), queryEntities, null);
    return new RetrievalContext("prof-1", "q", analysis, null, null, 10, seeds);
  }

  @Test
  void seedsFromQueryEntitiesAndWave1Candidates() {
    RecordingStore store = new RecordingStore();
    store.byName = List.of(new Entity("e-redis", "prof-1", "concept", "redis", "{}", T0));
    store.byMemory = List.of(new Entity("e-staging", "prof-1", "concept", "staging", "{}", T0));
    store.neighborhoodResult = List.of("e-redis", "e-staging");
    store.memoriesByEntities = List.of(mem("g1", "redis runs on staging"));

    RetrievalCandidate wave1 = new RetrievalCandidate(mem("m1", "we use redis"),
      RetrievalChannelType.FTS_MEMORY, 1, "we use redis");

    GraphChannel channel = new GraphChannel(store, 2, 20, 8);
    List<RetrievalCandidate> out = channel.retrieve(ctx(List.of("redis"), List.of(wave1)));

    // Both seed sources were consulted and unioned before traversal.
    assertThat(store.findEntitiesByNameCalled).isTrue();
    assertThat(store.entitiesForMemoriesArg).containsExactly("m1");
    assertThat(store.neighborhoodSeeds).containsExactlyInAnyOrder("e-redis", "e-staging");

    // Ranked GRAPH candidates from findMemoriesByEntities.
    assertThat(out).hasSize(1);
    assertThat(out.get(0).memory().id()).isEqualTo("g1");
    assertThat(out.get(0).channel()).isEqualTo(RetrievalChannelType.GRAPH);
    assertThat(out.get(0).rankInChannel()).isEqualTo(1);
  }

  @Test
  void returnsEmptyWithoutSeeds() {
    RecordingStore store = new RecordingStore();
    GraphChannel channel = new GraphChannel(store, 2, 20, 8);

    List<RetrievalCandidate> out = channel.retrieve(ctx(List.of(), List.of()));

    assertThat(out).isEmpty();
    assertThat(store.neighborhoodSeeds).isNull(); // no traversal when there are no seeds
  }

  @Test
  void isNonCriticalGraphChannel() {
    GraphChannel channel = new GraphChannel(new RecordingStore(), 2, 20, 8);
    assertThat(channel.type()).isEqualTo(RetrievalChannelType.GRAPH);
    assertThat(channel.critical()).isFalse();
  }

  /** Records the graph-method arguments the channel passes, returning canned results. */
  private static final class RecordingStore implements MemoryStore {
    List<Entity> byName = List.of();
    List<Entity> byMemory = List.of();
    List<String> neighborhoodResult = List.of();
    List<Memory> memoriesByEntities = List.of();

    boolean findEntitiesByNameCalled;
    List<String> entitiesForMemoriesArg;
    List<String> neighborhoodSeeds;

    @Override
    public List<Entity> findEntitiesByName(String profileId, List<String> names, int limit) {
      findEntitiesByNameCalled = true;
      return byName;
    }

    @Override
    public List<Entity> entitiesForMemories(String profileId, List<String> memoryIds, int limit) {
      entitiesForMemoriesArg = new ArrayList<>(memoryIds);
      return byMemory;
    }

    @Override
    public List<String> neighborhood(String profileId, List<String> seedEntityIds, int depth, int fanout) {
      neighborhoodSeeds = new ArrayList<>(seedEntityIds);
      return neighborhoodResult;
    }

    @Override
    public List<Memory> findMemoriesByEntities(String profileId, List<String> entityIds, int limit) {
      return memoriesByEntities;
    }

    // --- unused surface ---
    @Override public Profile getOrCreateProfile(String name) { throw new UnsupportedOperationException(); }
    @Override public java.util.Optional<Profile> findProfile(String name) { return java.util.Optional.empty(); }
    @Override public void insertMessages(String p, String s, List<Message> m) { }
    @Override public Memory insertMemory(String p, Memory m) { throw new UnsupportedOperationException(); }
    @Override public List<Memory> listMemories(String p, MemoryType t, String s) { return List.of(); }
    @Override public boolean forgetMemory(String p, String id) { return false; }
    @Override public List<dev.alvo.pieria.domain.ExportRow> exportProfile(String p) { return List.of(); }
    @Override public List<RecallCandidate> findRecallCandidates(String p, String q, int l) { return List.of(); }
  }
}
