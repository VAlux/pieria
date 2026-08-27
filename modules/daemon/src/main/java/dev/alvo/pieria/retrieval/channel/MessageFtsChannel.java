package dev.alvo.pieria.retrieval.channel;

import dev.alvo.pieria.retrieval.RetrievalChannel;
import dev.alvo.pieria.retrieval.RetrievalContext;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
import dev.alvo.pieria.storage.MemoryStore;

import java.util.List;

/**
 * FTS over raw stored messages: a lower-priority safety net for verbatim details the extractor may
 * have generalized away. Surfaces active memories from sessions whose messages matched.
 */
public final class MessageFtsChannel implements RetrievalChannel {

  private final MemoryStore store;

  public MessageFtsChannel(MemoryStore store) {
    this.store = store;
  }

  @Override
  public RetrievalChannelType type() {
    return RetrievalChannelType.FTS_MESSAGE;
  }

  @Override
  public List<RetrievalCandidate> retrieve(RetrievalContext ctx) {
    return RetrievalChannel.ranked(
      store.searchMemoriesByMessageFts(ctx.profileId(), ctx.ftsText(), ctx.limit()), type());
  }
}
