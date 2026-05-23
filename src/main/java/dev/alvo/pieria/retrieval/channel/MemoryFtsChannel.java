package dev.alvo.pieria.retrieval.channel;

import dev.alvo.pieria.domain.RetrievalCandidate;
import dev.alvo.pieria.domain.RetrievalChannelType;
import dev.alvo.pieria.retrieval.RetrievalChannel;
import dev.alvo.pieria.retrieval.RetrievalContext;
import dev.alvo.pieria.storage.MemoryStore;

import java.util.List;

/** Porter-stemmed FTS over active memory content (SPEC 7.1). Primary lexical channel. */
public final class MemoryFtsChannel implements RetrievalChannel {

  private final MemoryStore store;

  public MemoryFtsChannel(MemoryStore store) {
    this.store = store;
  }

  @Override
  public RetrievalChannelType type() {
    return RetrievalChannelType.FTS_MEMORY;
  }

  @Override
  public List<RetrievalCandidate> retrieve(RetrievalContext ctx) {
    return RetrievalChannel.ranked(
      store.searchMemoriesFts(ctx.profileId(), ctx.ftsText(), ctx.limit()), type());
  }
}
