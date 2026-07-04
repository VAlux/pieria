package dev.alvo.pieria.evaluation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live, dataset-backed benchmark runs against a <em>real</em> daemon. Skipped by default and never
 * run in CI: they boot a full daemon web stack ({@link LiveDaemon}) on a throwaway DB and require a
 * reachable model provider (Ollama) AND a local dataset file.
 *
 * <p>The LoCoMo entry is the anchor for the retrieval–memorization baseline (see
 * {@code docs/eval/BASELINE.md}). It runs against {@code datasets/locomo/locomo10.json} at the repo
 * root when present — no dataset env var required — and is skipped (not failed) when it is absent:
 *
 * <pre>{@code
 *   PIERIA_LIVE_EVAL=1 ./gradlew :eval:test --tests "*DaemonBenchmarkLiveTests*"
 * }</pre>
 *
 * Override the dataset location with {@code PIERIA_LOCOMO_DATASET}. LongMemEval stays opt-in behind
 * {@code PIERIA_LONGMEMEVAL_DATASET}. Faithfulness is judged as a second pass with a separate judge
 * gateway. The raw and judged reports land in {@code pieria-eval-reports/}.
 */
@EnabledIfEnvironmentVariable(named = "PIERIA_LIVE_EVAL", matches = "1|true")
class DaemonBenchmarkLiveTests {

  private static final Logger LOGGER = LoggerFactory.getLogger(DaemonBenchmarkLiveTests.class);

  private static final Path DEFAULT_LOCOMO_DATASET = Path.of("datasets", "locomo", "locomo10.json");

  @Test
  void runsLoCoMoAgainstRealDaemon() throws Exception {
    String override = System.getenv("PIERIA_LOCOMO_DATASET");
    Path dataset = override != null && !override.isBlank() ? Path.of(override) : DEFAULT_LOCOMO_DATASET;
    Assumptions.assumeTrue(Files.exists(dataset), () ->
      "LoCoMo dataset not found at " + dataset.toAbsolutePath()
        + " — place locomo10.json under datasets/ or set PIERIA_LOCOMO_DATASET");

    LOGGER.info("Running LoCoMo against a real daemon, dataset {}", dataset);

    try (LiveDaemon daemon = LiveDaemon.start()) {
      DaemonEvalClient client = new DaemonEvalClient(daemon.baseUrl());
      assertThat(client.healthy()).as("daemon health").isTrue();

      BenchmarkRunner runner = new BenchmarkRunner(client);
      EvaluationReport report = runner.runLoCoMo(dataset, BenchmarkRunner.DEFAULT_RUN_COUNT);
      assertThat(report.fixtures()).isNotEmpty();

      // Judge answer faithfulness with a separate judge gateway, then write the judged report.
      EvaluationReport judged;
      try (LiveModelGatewayFactory judge = LiveModelGatewayFactory.fromSpring()) {
        judged = new FaithfulnessJudgeRunner(judge.gateway()).judge(report);
      }

      Path written = new EvaluationReportWriter().write(judged, EvaluationReportWriter.DEFAULT_OUTPUT_DIRECTORY);
      EvaluationReport.Summary s = judged.summary();
      LOGGER.info("LoCoMo baseline — faithfulness={} hitRate={} mrr={} ingestMs={} recallMs={} report={}",
        String.format("%.3f", s.answerFaithfulness()),
        String.format("%.3f", s.retrievalHitRate()),
        String.format("%.3f", s.meanReciprocalRank()),
        s.latency().ingestionMs(), s.latency().recallMs(), written);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PIERIA_LONGMEMEVAL_DATASET", matches = ".+")
  void runsLongMemEvalAgainstRealDaemon() throws Exception {
    Path dataset = Path.of(System.getenv("PIERIA_LONGMEMEVAL_DATASET"));

    LOGGER.info("Running LongMemEval against a real daemon, dataset {}", dataset);

    try (LiveDaemon daemon = LiveDaemon.start()) {
      DaemonEvalClient client = new DaemonEvalClient(daemon.baseUrl());
      assertThat(client.healthy()).as("daemon health").isTrue();

      BenchmarkRunner runner = new BenchmarkRunner(client);
      EvaluationReport report = runner.runLongMemEval(dataset, BenchmarkRunner.DEFAULT_RUN_COUNT);
      assertThat(report.fixtures()).isNotEmpty();
    }
  }
}
