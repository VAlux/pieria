package dev.alvo.pieria.retrieval.rerank;

import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.retrieval.RetrievalDiagnostics.RerankDiagnostics;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.RerankLabel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks the small/fast model tier to label each candidate's relevance, then re-buckets on the
 * answer: everything {@code ESSENTIAL} first, then everything {@code RELATED}, with the incoming
 * order preserved inside each bucket and {@code IRRELEVANT} dropped.
 *
 * <p>Preserving the incoming order within a bucket is the whole trick. The model supplies a coarse
 * judgement it can actually make; the channel evidence RRF earned supplies the fine ordering the
 * model cannot. Neither is asked to do the other's job.
 *
 * <p>This class owns every failure mode except the timeout, which belongs to the caller that runs
 * it on a bounded thread. All of them land in the same place: hand the input back untouched.
 */
public class ModelReranker implements Reranker {

  static final String STAGE = "model";

  private static final Logger LOGGER = LoggerFactory.getLogger(ModelReranker.class);

  private final ModelGateway modelGateway;

  public ModelReranker(ModelGateway modelGateway) {
    this.modelGateway = modelGateway;
  }

  private static String snippet(RecallCandidate candidate, int maxChars) {
    String content = candidate.memory().content();
    if (content == null) {
      return "";
    }
    return content.length() <= maxChars ? content : content.substring(0, maxChars);
  }

  @Override
  public RerankOutcome rerank(RerankInput input) {
    long start = System.nanoTime();
    List<RecallCandidate> candidates = input.candidates();
    RerankSettings settings = input.settings();

    if (!settings.enabled() || !settings.modelEnabled() || candidates.size() < 2) {
      return RerankOutcome.passThrough(candidates, STAGE, elapsedMillis(start));
    }

    List<String> contents = candidates.stream()
      .map(candidate -> snippet(candidate, settings.snippetChars()))
      .toList();

    List<RerankLabel> labels;
    try {
      labels = modelGateway.rerankCandidates(input.query(), contents);
    } catch (RuntimeException e) {
      LOGGER.warn("rerank model call failed ({}); keeping fused order", e.toString());
      return RerankOutcome.passThrough(candidates, STAGE, elapsedMillis(start));
    }

    if (labels == null || labels.isEmpty()) {
      LOGGER.debug("rerank model returned no signal; keeping fused order");
      return RerankOutcome.passThrough(candidates, STAGE, elapsedMillis(start));
    }

    if (labels.size() != candidates.size()) {
      // Not partial data — a wrong-length list means we cannot know which label belongs to which
      // candidate, and applying it to the prefix would drop the wrong memory silently.
      LOGGER.warn("rerank model returned {} label(s) for {} candidate(s); keeping fused order",
        labels.size(), candidates.size());
      return RerankOutcome.passThrough(candidates, STAGE, elapsedMillis(start));
    }

    if (labels.stream().allMatch(label -> label == RerankLabel.IRRELEVANT)) {
      // Pieria treats abstention as correct behaviour, so an empty evidence list is defensible in
      // principle — but a flaky batch that labels everything irrelevant looks identical from here,
      // and honouring it would blank a good recall with nothing to signal it happened.
      LOGGER.warn("rerank model labelled all {} candidate(s) irrelevant; treating as no signal",
        candidates.size());
      return RerankOutcome.passThrough(candidates, STAGE, elapsedMillis(start));
    }

    List<RecallCandidate> essential = new ArrayList<>(candidates.size());
    List<RecallCandidate> related = new ArrayList<>(candidates.size());
    for (int i = 0; i < candidates.size(); i++) {
      switch (labels.get(i)) {
        case ESSENTIAL -> essential.add(candidates.get(i));
        case RELATED -> related.add(candidates.get(i));
        case IRRELEVANT -> { /* dropped */ }
      }
    }

    List<RecallCandidate> reranked = new ArrayList<>(essential.size() + related.size());
    reranked.addAll(essential);
    reranked.addAll(related);

    int dropped = candidates.size() - reranked.size();
    LOGGER.debug("rerank model essential={} related={} dropped={}",
      essential.size(), related.size(), dropped);
    return new RerankOutcome(List.copyOf(reranked),
      new RerankDiagnostics(STAGE, candidates.size(), reranked.size(), dropped,
        elapsedMillis(start), false));
  }

  private static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }
}
