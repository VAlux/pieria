package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.evaluation.EvaluationReport.CategoryScore;
import dev.alvo.pieria.evaluation.EvaluationReport.ConversationReport;
import dev.alvo.pieria.evaluation.EvaluationReport.QueryReport;
import dev.alvo.pieria.model.ModelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Judge-later pass: fills in answer faithfulness on conversation reports the {@link EvaluationRunner}
 * produced against the daemon. The daemon run records each question's expected and actual answer but
 * leaves the faithfulness flag {@code false}; this pass runs a judge {@link ModelGateway} over those
 * recorded pairs and returns new reports with the flag set and the aggregates recomputed. Everything
 * else (retrieval metrics, latency, evidence) is carried through unchanged.
 *
 * <p>Keeping judging separate from the daemon run means the expensive end-to-end pass never has to be
 * repeated to re-score answers — a written report can be re-judged with a different judge model.
 */
public final class FaithfulnessJudgeRunner {

  private static final Logger log = LoggerFactory.getLogger(FaithfulnessJudgeRunner.class);

  private final ModelGateway judge;

  public FaithfulnessJudgeRunner(ModelGateway judge) {
    this.judge = Objects.requireNonNull(judge, "judge");
  }

  public List<ConversationReport> judge(List<ConversationReport> conversations) {
    List<ConversationReport> judged = new ArrayList<>(conversations.size());
    for (int i = 0; i < conversations.size(); i++) {
      ConversationReport conversation = conversations.get(i);
      log.info("judging [{}/{}] {} — {} answers",
        i + 1, conversations.size(), conversation.name(), conversation.queries().size());
      judged.add(judgeConversation(conversation));
    }
    return judged;
  }

  private ConversationReport judgeConversation(ConversationReport conversation) {
    List<QueryReport> judged = new ArrayList<>(conversation.queries().size());
    for (QueryReport query : conversation.queries()) {
      boolean faithful = judge.judgeAnswerFaithfulness(
        query.question(), query.expectedAnswer(), query.actualAnswer());
      judged.add(new QueryReport(
        query.question(),
        query.category(),
        query.expectedAnswer(),
        query.actualAnswer(),
        faithful,
        query.expectedEvidence(),
        query.retrievedMemories(),
        query.hitRate(),
        query.reciprocalRank(),
        query.latencyMs()));
    }

    CategoryScore score = EvaluationReport.score(judged);
    log.info("{} — faithfulness {}", conversation.name(),
      String.format("%.3f", score.answerFaithfulness()));

    return new ConversationReport(
      conversation.name(),
      conversation.turns(),
      conversation.memoriesStored(),
      score.answerFaithfulness(),
      conversation.retrievalHitRate(),
      conversation.meanReciprocalRank(),
      conversation.latency(),
      judged);
  }
}
