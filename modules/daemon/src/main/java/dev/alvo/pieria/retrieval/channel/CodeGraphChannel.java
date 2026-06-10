package dev.alvo.pieria.retrieval.channel;

import dev.alvo.pieria.code.CodePayload;
import dev.alvo.pieria.domain.code.CodeSymbol;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.retrieval.RetrievalChannel;
import dev.alvo.pieria.retrieval.RetrievalContext;
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
    if (!codeStore.isCodeIndexPresent(ctx.profileId())) {
      return List.of();
    }

    LinkedHashSet<String> seedSymbolIds = new LinkedHashSet<>();

    // (a) symbols named in the query (entities + FTS terms).
    List<String> names = queryNames(ctx);
    if (!names.isEmpty()) {
      for (CodeSymbol s : codeStore.findSymbolsByName(ctx.profileId(), names, seedLimit)) {
        seedSymbolIds.add(s.id());
      }
      for (CodeSymbol s : codeStore.findSymbolsByQualifiedName(ctx.profileId(), names, seedLimit)) {
        seedSymbolIds.add(s.id());
      }
    }

    // (b) symbol-id provenance carried by wave-1 candidate memories.
    for (RetrievalCandidate cand : ctx.seedCandidates()) {
      for (String id : CodePayload.symbolIds(cand.memory().payload())) {
        seedSymbolIds.add(id);
        if (seedSymbolIds.size() >= seedLimit * 4) {
          break;
        }
      }
    }

    if (seedSymbolIds.isEmpty()) {
      return List.of();
    }

    List<String> reached = codeStore.symbolNeighborhood(
      ctx.profileId(), List.copyOf(seedSymbolIds), depth, fanout, minConfidence);
    if (reached.isEmpty()) {
      return List.of();
    }

    List<Memory> memories = store.findCodeMemoriesBySymbolIds(ctx.profileId(), reached, ctx.limit());
    return RetrievalChannel.ranked(memories, type());
  }

  private static List<String> queryNames(RetrievalContext ctx) {
    if (ctx.analysis() == null) {
      return List.of();
    }
    List<String> names = new ArrayList<>(ctx.analysis().entities());
    names.addAll(ctx.analysis().ftsTerms());
    return names.stream().filter(n -> n != null && !n.isBlank()).distinct().toList();
  }
}
