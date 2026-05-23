package dev.alvo.pieria.retrieval.channel;

import dev.alvo.pieria.domain.RetrievalCandidate;
import dev.alvo.pieria.domain.RetrievalChannelType;
import dev.alvo.pieria.retrieval.RetrievalChannel;
import dev.alvo.pieria.retrieval.RetrievalContext;
import dev.alvo.pieria.storage.MemoryStore;

import java.util.List;

/** Exact fact-key lookup: the query's ranked topic keys mapped to known {@code topic_key}s. */
public final class ExactKeyChannel implements RetrievalChannel {

  private final MemoryStore store;

  public ExactKeyChannel(MemoryStore store) {
    this.store = store;
  }

  @Override
  public RetrievalChannelType type() {
    return RetrievalChannelType.EXACT_KEY;
  }

  @Override
  public List<RetrievalCandidate> retrieve(RetrievalContext ctx) {
    List<String> keys = ctx.analysis() == null ? List.of() : ctx.analysis().topicKeys();
    if (keys.isEmpty()) {
      return List.of();
    }
    return RetrievalChannel.ranked(store.exactKeyLookup(ctx.profileId(), keys, ctx.limit()), type());
  }
}
