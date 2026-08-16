package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.evaluation.EvaluationReport.ConversationReport;
import dev.alvo.pieria.evaluation.EvaluationReport.QueryReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Score;
import dev.alvo.pieria.evaluation.EvaluationReport.Spend;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.ModelGateway.AnswerVerdict;
import dev.alvo.pieria.model.usage.InferenceUsageAccumulator;
import dev.alvo.pieria.model.usage.InferenceUsageSink;
import dev.alvo.pieria.model.usage.TierUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Judge-later pass: turns the raw material {@link EvaluationRunner} recorded against the daemon into
 * scores. For each question it fills in three things:
 *
 * <ol>
 *   <li><strong>the verdict</strong> — correct / wrong / abstained. Declining to answer is scored
 *       apart from answering wrongly, because for a memory layer they mean opposite things: one is
 *       a fact that was never stored, the other is a hallucination.</li>
 *   <li><strong>gate 1, extraction</strong> — whether the gold fact survived ingestion into any
 *       stored memory, judged over the shortlist the run recorded.</li>
 *   <li><strong>gate 2, retrieval</strong> — whether recall actually surfaced a memory carrying it.
 *       Only asked when gate 1 passed: recall cannot surface what was never stored, and scoring it
 *       as a retrieval miss would blame the wrong stage.</li>
 * </ol>
 *
 * <p>Adversarial questions ({@code expectAbstention}) skip both gates — there is no gold fact to
 * find — and invert the verdict: the trap answer being asserted is the failure, and declining is the
 * correct result.
 *
 * <p>Keeping judging separate from the daemon run means the expensive end-to-end pass never has to
 * be repeated to re-score answers: a written report carries the retrieved memories and the
 * extraction shortlist, so it can be re-judged with a different judge model.
 */
public final class JudgeRunner {

  private static final Logger log = LoggerFactory.getLogger(JudgeRunner.class);

  private final ModelGateway judge;
  private final InferenceUsageAccumulator usage = new InferenceUsageAccumulator();

  public JudgeRunner(ModelGateway judge) {
    this.judge = Objects.requireNonNull(judge, "judge");
  }

  /**
   * Judges every conversation, accumulating what the judging itself cost.
   *
   * <p>The judge gateway runs outside the daemon, so its calls reach no profile's spend counters —
   * {@code InferenceUsageSink} hands unbound threads a no-op accumulator and discards the writes. The
   * pass therefore binds its own accumulator for the duration. Judging is a large share of a run's
   * bill, so leaving it unmeasured would make the reported cost quietly wrong rather than merely
   * incomplete.
   */
  public List<ConversationReport> judge(List<ConversationReport> conversations) {
    List<ConversationReport> judged = new ArrayList<>(conversations.size());
    try (InferenceUsageSink.Binding ignored = InferenceUsageSink.bind(usage)) {
      for (int i = 0; i < conversations.size(); i++) {
        ConversationReport conversation = conversations.get(i);
        log.info("judging [{}/{}] {} — {} questions",
          i + 1, conversations.size(), conversation.name(), conversation.queries().size());
        judged.add(judgeConversation(conversation));
      }
    }
    return judged;
  }

  /**
   * What judging has cost so far, costed with {@code prices} (keyed by lower-case tier name) exactly
   * as the daemon costs the pipeline's spend. An unpriced tier contributes tokens but no dollars.
   */
  public Spend spend(Map<String, PieriaProperties.Stats.TierPrice> prices) {
    Map<String, PieriaProperties.Stats.TierPrice> table = prices == null ? Map.of() : prices;
    List<Spend.TierSpend> tiers = new ArrayList<>();
    long prompt = 0;
    long completion = 0;
    double cost = 0;
    boolean priced = false;
    for (var entry : usage.snapshot().entrySet()) {
      String tier = entry.getKey().name().toLowerCase(Locale.ROOT);
      TierUsage used = entry.getValue();
      PieriaProperties.Stats.TierPrice price = table.get(tier);
      double tierCost = price == null ? 0.0
        : used.promptTokens() / 1_000_000.0 * price.inputPrice()
          + used.completionTokens() / 1_000_000.0 * price.outputPrice();
      priced |= price != null && (price.inputPrice() > 0 || price.outputPrice() > 0);
      tiers.add(new Spend.TierSpend(tier, used.calls(), used.promptTokens(),
        used.completionTokens(), tierCost));
      prompt += used.promptTokens();
      completion += used.completionTokens();
      cost += tierCost;
    }
    return new Spend(tiers, prompt, completion, cost, priced);
  }

  private ConversationReport judgeConversation(ConversationReport conversation) {
    List<QueryReport> judged = new ArrayList<>(conversation.queries().size());
    for (QueryReport query : conversation.queries()) {
      judged.add(judgeQuery(query));
    }

    Score score = EvaluationReport.score(judged);
    log.info("{} — accuracy {} (coverage {}, retrieval {})", conversation.name(),
      format(score.accuracy()), format(score.extractionCoverage()), format(score.retrievalRecall()));

    return new ConversationReport(
      conversation.name(),
      conversation.turns(),
      conversation.memoriesStored(),
      score,
      conversation.latency(),
      conversation.spend(),
      judged,
      conversation.storedMemoryTexts());
  }

  private QueryReport judgeQuery(QueryReport query) {
    AnswerVerdict verdict = judge.judgeAnswer(
      query.question(), query.expectedAnswer(), query.actualAnswer());

    Boolean extracted = null;
    Boolean retrieved = null;
    if (!query.expectAbstention()) {
      extracted = judge.judgeEvidenceSupport(
        query.question(), query.expectedAnswer(), query.extractionCandidates());
      // Asking gate 2 after gate 1 failed would score a retrieval miss for a fact that was never
      // there to retrieve, so leave it null and let the conditional rate skip the question.
      retrieved = extracted
        ? judge.judgeEvidenceSupport(query.question(), query.expectedAnswer(), query.retrievedMemories())
        : null;
    }

    return new QueryReport(
      query.question(),
      query.category(),
      query.expectAbstention(),
      query.expectedAnswer(),
      query.actualAnswer(),
      verdict,
      extracted,
      retrieved,
      query.expectedEvidence(),
      query.retrievedMemories(),
      query.extractionCandidates(),
      query.latencyMs());
  }

  private static String format(double value) {
    return String.format("%.3f", value);
  }
}
