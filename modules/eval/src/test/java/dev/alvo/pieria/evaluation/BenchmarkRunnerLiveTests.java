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
 * Live, dataset-backed benchmark runs. These are skipped by default and never execute in CI: they
 * require a reachable model provider AND a local dataset file.
 *
 * <p>The LoCoMo entry is the anchor for the retrieval–memorization baseline (see
 * {@code docs/eval/BASELINE.md}). It runs against {@code datasets/locomo/locomo10.json} at the repo
 * root when present — no dataset env var required — and is skipped (not failed) when it is absent:
 *
 * <pre>{@code
 *   PIERIA_LIVE_EVAL=1 ./gradlew :eval:test --tests "*BenchmarkRunnerLiveTests*"
 * }</pre>
 *
 * Override the dataset location with {@code PIERIA_LOCOMO_DATASET}. LongMemEval stays opt-in behind
 * {@code PIERIA_LONGMEMEVAL_DATASET}. The averaged report lands in {@code pieria-eval-reports/}.
 */
@EnabledIfEnvironmentVariable(named = "PIERIA_LIVE_EVAL", matches = "1|true")
class BenchmarkRunnerLiveTests {

  private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkRunnerLiveTests.class);

	private static final Path DEFAULT_LOCOMO_DATASET = Path.of("datasets", "locomo", "locomo10.json");

	@Test
	void runsLoCoMoAgainstLiveModel() throws Exception {
		String override = System.getenv("PIERIA_LOCOMO_DATASET");
		Path dataset = override != null && !override.isBlank() ? Path.of(override) : DEFAULT_LOCOMO_DATASET;
		Assumptions.assumeTrue(Files.exists(dataset), () ->
			"LoCoMo dataset not found at " + dataset.toAbsolutePath()
				+ " — place locomo10.json under datasets/ or set PIERIA_LOCOMO_DATASET");

		LOGGER.info("Running LoCoMo against live model, dataset {}", dataset);

		try (LiveModelGatewayFactory live = LiveModelGatewayFactory.fromSpring()) {
			BenchmarkRunner runner = new BenchmarkRunner(live.properties(), live.gatewayFactory());
			EvaluationReport report = runner.runLoCoMo(dataset, BenchmarkRunner.DEFAULT_RUN_COUNT);
			assertThat(report.fixtures()).isNotEmpty();

			Path written = new EvaluationReportWriter().write(report, EvaluationReportWriter.DEFAULT_OUTPUT_DIRECTORY);
			EvaluationReport.Summary s = report.summary();
			LOGGER.info("LoCoMo baseline — faithfulness={} hitRate={} mrr={} ingestMs={} recallMs={} report={}",
				String.format("%.3f", s.answerFaithfulness()),
				String.format("%.3f", s.retrievalHitRate()),
				String.format("%.3f", s.meanReciprocalRank()),
				s.latency().ingestionMs(), s.latency().recallMs(), written);
		}
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "PIERIA_LONGMEMEVAL_DATASET", matches = ".+")
	void runsLongMemEvalAgainstLiveModel() throws Exception {
		Path dataset = Path.of(System.getenv("PIERIA_LONGMEMEVAL_DATASET"));

    LOGGER.info("Running LongMemEval against live model against dataset {}", dataset);

		try (LiveModelGatewayFactory live = LiveModelGatewayFactory.fromSpring()) {
			BenchmarkRunner runner = new BenchmarkRunner(live.properties(), live.gatewayFactory());
			EvaluationReport report = runner.runLongMemEval(dataset, BenchmarkRunner.DEFAULT_RUN_COUNT);
			assertThat(report.fixtures()).isNotEmpty();
		}
	}
}
