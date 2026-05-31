package dev.alvo.pieria.retrieval.channel;

import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
import dev.alvo.pieria.retrieval.RetrievalChannel;
import dev.alvo.pieria.retrieval.RetrievalContext;
import dev.alvo.pieria.storage.MemoryStore;

import java.util.List;

/**
 * Semantic similarity using the embedded raw query. Best-effort: returns nothing when
 * vector search is unavailable or the query embedding is absent, so recall degrades to FTS + keyed.
 */
public final class DirectVectorChannel implements RetrievalChannel {

  private final MemoryStore store;

  public DirectVectorChannel(MemoryStore store) {
    this.store = store;
  }

  @Override
  public RetrievalChannelType type() {
    return RetrievalChannelType.DIRECT_VECTOR;
  }

  @Override
  public boolean critical() {
    return false;
  }

  @Override
  public List<RetrievalCandidate> retrieve(RetrievalContext ctx) {
    float[] embedding = ctx.queryEmbedding();
    if (embedding == null || embedding.length == 0) {
      return List.of();
    }
    return RetrievalChannel.ranked(store.vectorSearch(ctx.profileId(), embedding, ctx.limit()), type());
  }
}
