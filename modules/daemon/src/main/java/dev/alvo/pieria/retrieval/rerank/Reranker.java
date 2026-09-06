package dev.alvo.pieria.retrieval.rerank;

/**
 * Re-orders (and may shorten) the fused candidates before the recall {@code limit} is applied.
 *
 * <p>Two contracts, and they are absolute. An implementation must never throw — a reranker is a
 * precision improvement, and a recall that worked without one must not start failing because one
 * was added. And it must never widen the list it received: a reranker ranks candidates, it does not
 * retrieve them.
 *
 * <p>This is also the seam a dedicated reranker model (a bge-reranker over Ollama, say) would slot
 * into as a third implementation — though it is a seam in shape only. {@code RetrievalService}
 * holds {@code SemanticRescorer} and {@code ModelReranker} as fields typed to their concrete
 * classes and calls them by name, not through a {@code List<Reranker>}, and the off-thread bound
 * a dedicated model would need lives in {@code RetrievalService.runModelRerank}, not behind this
 * interface. A third implementation is a small, well-located edit at that call site — not a change
 * the pipeline needs no awareness of.
 */
public interface Reranker {

  RerankOutcome rerank(RerankInput input);
}
