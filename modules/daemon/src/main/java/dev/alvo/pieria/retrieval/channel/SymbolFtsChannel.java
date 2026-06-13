package dev.alvo.pieria.retrieval.channel;

import dev.alvo.pieria.domain.code.CodeSymbol;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.retrieval.RetrievalChannel;
import dev.alvo.pieria.retrieval.RetrievalContext;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
import dev.alvo.pieria.storage.CodeIndexStore;
import dev.alvo.pieria.storage.MemoryStore;

import java.util.List;

/**
 * First-wave, best-effort code channel: FTS over the symbol index, resolved back to the derived code
 * memories that carry those symbols as provenance. Returns {@code Memory} candidates so fusion and
 * synthesis are unchanged. Skips cleanly (empty) when no code index exists for the profile.
 */
public final class SymbolFtsChannel implements RetrievalChannel {

  private final MemoryStore store;
  private final CodeIndexStore codeStore;

  public SymbolFtsChannel(MemoryStore store, CodeIndexStore codeStore) {
    this.store = store;
    this.codeStore = codeStore;
  }

  @Override
  public RetrievalChannelType type() {
    return RetrievalChannelType.SYMBOL_FTS;
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
    List<CodeSymbol> symbols = codeStore.searchSymbolsFts(ctx.profileId(), ctx.ftsText(), ctx.limit());
    if (symbols.isEmpty()) {
      return List.of();
    }
    List<String> symbolIds = symbols.stream().map(CodeSymbol::id).distinct().toList();
    List<Memory> memories = store.findCodeMemoriesBySymbolIds(ctx.profileId(), symbolIds, ctx.limit());
    return RetrievalChannel.ranked(memories, type());
  }
}
