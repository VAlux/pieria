package dev.alvo.pieria.retrieval.channel;

import dev.alvo.pieria.domain.RetrievalCandidate;
import dev.alvo.pieria.domain.RetrievalChannelType;
import dev.alvo.pieria.retrieval.RetrievalChannel;
import dev.alvo.pieria.retrieval.RetrievalContext;
import dev.alvo.pieria.storage.MemoryStore;

import java.util.List;

/**
 * Similarity to the embedded HyDE statement — what the <em>answer</em> would look like — surfacing
 * results the direct embedding misses, especially abstract/multi-hop queries (SPEC 7.1). Best-effort.
 */
public final class HydeVectorChannel implements RetrievalChannel {

  private final MemoryStore store;

  public HydeVectorChannel(MemoryStore store) {
    this.store = store;
  }

  @Override
  public RetrievalChannelType type() {
    return RetrievalChannelType.HYDE_VECTOR;
  }

  @Override
  public boolean critical() {
    return false;
  }

  @Override
  public List<RetrievalCandidate> retrieve(RetrievalContext ctx) {
    float[] embedding = ctx.hydeEmbedding();
    if (embedding == null || embedding.length == 0) {
      return List.of();
    }

    return RetrievalChannel.ranked(store.vectorSearch(ctx.profileId(), embedding, ctx.limit()), type());
  }
}
