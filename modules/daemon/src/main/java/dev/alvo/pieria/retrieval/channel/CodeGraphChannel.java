package dev.alvo.pieria.retrieval.channel;

import dev.alvo.pieria.code.CodePayload;
import dev.alvo.pieria.domain.code.CodeSymbol;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.retrieval.RetrievalChannel;
import dev.alvo.pieria.retrieval.RetrievalContext;
import dev.alvo.pieria.retrieval.model.GraphEvidence;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
import dev.alvo.pieria.storage.CodeIndexStore;
import dev.alvo.pieria.storage.MemoryStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Second-wave, best-effort code channel mirroring {@code GraphChannel}: seeds symbols from (a) the
 * query's terms/entities and (b) the symbol-id provenance of wave-1 candidate memories, expands the
 * precise {@code code_edges} neighborhood bounded by depth/fanout and {@code minConfidence}, then
 * resolves the reached symbols back to their derived code memories. Non-critical: a failure or
 * timeout contributes nothing and never fails recall.
 */
public final class CodeGraphChannel implements RetrievalChannel {

  private final MemoryStore store;
  private final CodeIndexStore codeStore;
  private final int depth;
  private final int fanout;
  private final int seedLimit;
  private final EdgeConfidence minConfidence;

  public CodeGraphChannel(MemoryStore store, CodeIndexStore codeStore,
                          int depth, int fanout, int seedLimit, EdgeConfidence minConfidence) {
    this.store = store;
    this.codeStore = codeStore;
    this.depth = depth;
    this.fanout = fanout;
    this.seedLimit = seedLimit;
    this.minConfidence = minConfidence;
  }

  private static List<String> queryNames(RetrievalContext ctx) {
    if (ctx.analysis() == null) {
      return List.of();
    }
    List<String> names = new ArrayList<>(ctx.analysis().entities());
    names.addAll(ctx.analysis().ftsTerms());
    return names.stream().filter(n -> n != null && !n.isBlank()).distinct().toList();
  }

  @Override
  public RetrievalChannelType type() {
    return RetrievalChannelType.CODE_GRAPH;
  }

  @Override
  public boolean critical() {
    return false;
  }

  @Override
  public List<RetrievalCandidate> retrieve(RetrievalContext ctx) {
    return retrieveWithEvidence(ctx).candidates();
  }

  @Override
  public ChannelResult retrieveWithEvidence(RetrievalContext ctx) {
    if (!codeStore.isCodeIndexPresent(ctx.profileId())) {
      return ChannelResult.of(List.of());
    }

    // (a) symbols named in the query (entities + FTS terms).
    LinkedHashSet<String> querySeedIds = new LinkedHashSet<>();
    List<String> names = queryNames(ctx);
    if (!names.isEmpty()) {
      for (CodeSymbol s : codeStore.findSymbolsByName(ctx.profileId(), names, seedLimit)) {
        querySeedIds.add(s.id());
      }
      for (CodeSymbol s : codeStore.findSymbolsByQualifiedName(ctx.profileId(), names, seedLimit)) {
        querySeedIds.add(s.id());
      }
    }

    // (b) symbol-id provenance carried by wave-1 candidate memories.
    LinkedHashSet<String> seedSymbolIds = new LinkedHashSet<>(querySeedIds);
    for (RetrievalCandidate cand : ctx.seedCandidates()) {
      for (String id : CodePayload.symbolIds(cand.memory().payload())) {
        seedSymbolIds.add(id);
        if (seedSymbolIds.size() >= seedLimit * 4) {
          break;
        }
      }
    }

    if (seedSymbolIds.isEmpty()) {
      return ChannelResult.of(List.of());
    }

    // Edges touching the seeds themselves (not the BFS closure) are the direct "who calls X /
    // what does X call" answers; they go to synthesis as evidence lines, bypassing fusion.
    // When the query names symbols, evidence is drawn from those alone: the wave-1 provenance
    // symbols (every symbol of every candidate file) would flood the capped evidence list with
    // edges unrelated to what was asked. Provenance seeds are the fallback for queries that
    // don't name any symbol.
    List<String> evidenceSeeds = querySeedIds.isEmpty()
      ? List.copyOf(seedSymbolIds)
      : List.copyOf(querySeedIds);
    List<GraphEvidence> evidence = edgeEvidence(ctx.profileId(), evidenceSeeds);

    List<String> reached = codeStore.symbolNeighborhood(
      ctx.profileId(), List.copyOf(seedSymbolIds), depth, fanout, minConfidence);
    if (reached.isEmpty()) {
      return new ChannelResult(List.of(), evidence);
    }

    List<Memory> memories = store.findCodeMemoriesBySymbolIds(ctx.profileId(), reached, ctx.limit());
    return new ChannelResult(RetrievalChannel.ranked(memories, type()), evidence);
  }

  private List<GraphEvidence> edgeEvidence(String profileId, List<String> seedSymbolIds) {
    return codeStore.findEdgesTouching(profileId, seedSymbolIds, minConfidence, fanout).stream()
      .map(e -> new GraphEvidence(
        e.src().qualifiedName(), e.src().path(),
        e.edge().relation().wire(),
        e.dst() != null ? e.dst().qualifiedName() : e.edge().dstRef(),
        e.dst() != null ? e.dst().path() : null,
        e.edge().confidence().wire()))
      .toList();
  }
}
