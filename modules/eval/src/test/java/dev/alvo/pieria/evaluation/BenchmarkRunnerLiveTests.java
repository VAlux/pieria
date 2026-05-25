package dev.alvo.pieria.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live, dataset-backed benchmark runs. These are skipped by default and never execute in CI: they
 * require a reachable model provider AND a local dataset file. Enable with environment variables:
 *
 * <pre>{@code
 *   PIERIA_LIVE_EVAL=1 \
 *   PIERIA_LOCOMO_DATASET=/data/locomo10.json \
 *   PIERIA_LONGMEMEVAL_DATASET=/data/longmemeval_s.json \
 *   ./gradlew :eval:test
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "PIERIA_LIVE_EVAL", matches = "1|true")
class BenchmarkRunnerLiveTests {

  private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkRunnerLiveTests.class);

	@Test
	@EnabledIfEnvironmentVariable(named = "PIERIA_LOCOMO_DATASET", matches = ".+")
	void runsLoCoMoAgainstLiveModel() throws Exception {
		Path dataset = Path.of(System.getenv("PIERIA_LOCOMO_DATASET"));

    LOGGER.info("Running LoCoMo against live model against dataset {}", dataset);

		try (LiveModelGatewayFactory live = LiveModelGatewayFactory.fromSpring()) {
			BenchmarkRunner runner = new BenchmarkRunner(live.properties(), live.gatewayFactory());
			EvaluationReport report = runner.runLoCoMo(dataset, BenchmarkRunner.DEFAULT_RUN_COUNT);
			assertThat(report.fixtures()).isNotEmpty();
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
