package dev.alvo.pieria.retrieval.channel;

import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
import dev.alvo.pieria.retrieval.RetrievalChannel;
import dev.alvo.pieria.retrieval.RetrievalContext;
import dev.alvo.pieria.storage.MemoryStore;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Second-wave, best-effort retrieval channel that traverses the entity-relation graph.
 * Unlike the five primary channels it does not run in the initial parallel fan-out: it is seeded
 * from (a) the entities named in the query analysis and (b) the entities attached to the candidates
 * the primary channels already surfaced (carried on {@link RetrievalContext#seedCandidates()}). It
 * expands the active-edge neighborhood a bounded number of hops, then surfaces the active memories
 * reachable via provenance edges. All traversal is indexed SQL — no model call at recall time.
 *
 * <p>Marked non-critical: a failure or timeout contributes nothing and never fails recall.
 */
public final class GraphChannel implements RetrievalChannel {

  private final MemoryStore store;
  private final int depth;
  private final int fanout;
  private final int seedLimit;

  public GraphChannel(MemoryStore store, int depth, int fanout, int seedLimit) {
    this.store = store;
    this.depth = depth;
    this.fanout = fanout;
    this.seedLimit = seedLimit;
  }

  @Override
  public RetrievalChannelType type() {
    return RetrievalChannelType.GRAPH;
  }

  @Override
  public boolean critical() {
    return false;
  }

  @Override
  public List<RetrievalCandidate> retrieve(RetrievalContext ctx) {
    LinkedHashSet<String> seedEntityIds = new LinkedHashSet<>();

    // (a) entities named in the query.
    List<String> queryEntities = ctx.analysis() == null ? List.of() : ctx.analysis().entities();
    if (!queryEntities.isEmpty()) {
      for (Entity e : store.findEntitiesByName(ctx.profileId(), queryEntities, seedLimit)) {
        seedEntityIds.add(e.id());
      }
    }

    // (b) entities attached to the top candidates the primary channels surfaced.
    List<String> wave1MemoryIds = ctx.seedCandidates().stream()
      .map(c -> c.memory().id())
      .distinct()
      .limit(seedLimit)
      .toList();
    if (!wave1MemoryIds.isEmpty()) {
      for (Entity e : store.entitiesForMemories(ctx.profileId(), wave1MemoryIds, seedLimit)) {
        seedEntityIds.add(e.id());
      }
    }

    if (seedEntityIds.isEmpty()) {
      return List.of();
    }

    List<String> reached = store.neighborhood(ctx.profileId(), List.copyOf(seedEntityIds), depth, fanout);
    if (reached.isEmpty()) {
      return List.of();
    }

    List<Memory> memories = store.findMemoriesByEntities(ctx.profileId(), reached, ctx.limit());
    return RetrievalChannel.ranked(memories, type());
  }
}
