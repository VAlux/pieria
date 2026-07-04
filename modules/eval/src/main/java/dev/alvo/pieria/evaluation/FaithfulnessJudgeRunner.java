package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.evaluation.EvaluationReport.FixtureReport;
import dev.alvo.pieria.evaluation.EvaluationReport.RecallReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Summary;
import dev.alvo.pieria.model.ModelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Judge-later pass: fills in answer faithfulness on a report the {@link EvaluationRunner} produced
 * against the daemon. The daemon run records each query's expected and actual answer but leaves the
 * faithfulness flag {@code false}; this pass runs a judge {@link ModelGateway} over those recorded
 * pairs and returns a new report with the flag set and the fixture/summary faithfulness aggregates
 * recomputed. Everything else (retrieval metrics, latency, evidence) is carried through unchanged.
 *
 * <p>Keeping judging separate from the daemon run means the expensive end-to-end pass never has to
 * be repeated to re-score answers — you can re-judge a written report with a different judge model.
 */
public final class FaithfulnessJudgeRunner {

  private static final Logger log = LoggerFactory.getLogger(FaithfulnessJudgeRunner.class);

  private final ModelGateway judge;

  public FaithfulnessJudgeRunner(ModelGateway judge) {
    this.judge = Objects.requireNonNull(judge, "judge");
  }

  public EvaluationReport judge(EvaluationReport report) {
    List<FixtureReport> judgedFixtures = new ArrayList<>(report.fixtures().size());
    for (FixtureReport fixture : report.fixtures()) {
      judgedFixtures.add(judgeFixture(fixture));
    }
    Summary summary = rescoreSummary(report.summary(), judgedFixtures);
    return new EvaluationReport(report.generatedAt(), judgedFixtures, summary);
  }

  private FixtureReport judgeFixture(FixtureReport fixture) {
    List<RecallReport> judged = new ArrayList<>(fixture.recalls().size());
    int faithfulCount = 0;
    for (RecallReport recall : fixture.recalls()) {
      boolean faithful = judge.judgeAnswerFaithfulness(
        recall.query(), recall.expectedAnswer(), recall.actualAnswer());
      if (faithful) {
        faithfulCount++;
      }
      judged.add(new RecallReport(
        recall.query(),
        recall.expectedEvidence(),
        recall.actualEvidence(),
        recall.hitRate(),
        recall.reciprocalRank(),
        faithful,
        recall.expectedAnswer(),
        recall.actualAnswer(),
        recall.latencyMs()));
    }
    double faithfulness = judged.isEmpty() ? 0.0 : (double) faithfulCount / judged.size();
    log.info("{} — faithfulness {}/{} ({})", fixture.fixtureName(), faithfulCount, judged.size(),
      String.format("%.3f", faithfulness));

    return new FixtureReport(
      fixture.fixtureName(),
      fixture.extraction(),
      judged,
      fixture.retrievalHitRate(),
      fixture.meanReciprocalRank(),
      faithfulness,
      fixture.latency(),
      fixture.tokenUsage());
  }

  private static Summary rescoreSummary(Summary summary, List<FixtureReport> fixtures) {
    double faithfulness = fixtures.isEmpty() ? 0.0 : fixtures.stream()
      .mapToDouble(FixtureReport::answerFaithfulness)
      .average()
      .orElse(0.0);
    return new Summary(
      summary.fixtureCount(),
      summary.extractionPrecision(),
      summary.extractionRecall(),
      summary.retrievalHitRate(),
      summary.meanReciprocalRank(),
      faithfulness,
      summary.latency(),
      summary.tokenUsage());
  }
}
