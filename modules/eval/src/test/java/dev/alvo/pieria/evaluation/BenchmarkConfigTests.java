package dev.alvo.pieria.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkConfigTests {

	@Test
	void defaultsRunTheWholeDatasetOnceWithJudging() {
		BenchmarkConfig config = BenchmarkConfig.defaults();

		assertThat(config.dataset()).isEqualTo("datasets/locomo/locomo10.json");
		// No --config: the run uses the daemon's bundled defaults, not the operator's config file.
		assertThat(config.configFile()).isNull();
		assertThat(config.configPath()).isNull();
		assertThat(config.conversations()).isZero();
		assertThat(config.conversationIds()).isEmpty();
		assertThat(config.sessions()).isZero();
		assertThat(config.questions()).isZero();
		assertThat(config.categories()).isEmpty();
		assertThat(config.runs()).isEqualTo(1);
		assertThat(config.recallLimit()).isEqualTo(10);
		assertThat(config.outputDirectory()).isEqualTo("pieria-eval-reports");
		assertThat(config.judge()).isTrue();
		assertThat(config.dryRun()).isFalse();
	}

	@Test
	void parsesEveryFlag() {
		BenchmarkConfig config = BenchmarkConfig.parse(
			"--dataset=/tmp/locomo.json", "--config=/tmp/pieria.properties",
			"--conversations=3", "--sessions=2", "--questions=15",
			"--categories=1,4", "--runs=2", "--recall-limit=25", "--out=/tmp/reports", "--no-judge",
			"--dry-run");

		assertThat(config.datasetPath()).hasToString("/tmp/locomo.json");
		assertThat(config.configPath()).hasToString("/tmp/pieria.properties");
		assertThat(config.conversations()).isEqualTo(3);
		assertThat(config.sessions()).isEqualTo(2);
		assertThat(config.questions()).isEqualTo(15);
		assertThat(config.categories()).containsExactly(1, 4);
		assertThat(config.runs()).isEqualTo(2);
		assertThat(config.recallLimit()).isEqualTo(25);
		assertThat(config.outputPath()).hasToString("/tmp/reports");
		assertThat(config.judge()).isFalse();
		assertThat(config.dryRun()).isTrue();
	}

	@Test
	void conversationsTakesEitherACountOrAnIdList() {
		BenchmarkConfig count = BenchmarkConfig.parse("--conversations=4");
		assertThat(count.conversations()).isEqualTo(4);
		assertThat(count.conversationIds()).isEmpty();

		BenchmarkConfig ids = BenchmarkConfig.parse("--conversations=conv-26, conv-30,conv-26");
		assertThat(ids.conversations()).isZero();
		assertThat(ids.conversationIds()).containsExactly("conv-26", "conv-30");
	}

	@Test
	void categoryFilterAcceptsEverythingWhenUnset() {
		assertThat(BenchmarkConfig.defaults().acceptsCategory(5)).isTrue();
		assertThat(BenchmarkConfig.parse("--categories=1,2").acceptsCategory(2)).isTrue();
		assertThat(BenchmarkConfig.parse("--categories=1,2").acceptsCategory(5)).isFalse();
	}

	@Test
	void describeSubsetRendersTheActiveSlice() {
		assertThat(BenchmarkConfig.defaults().describeSubset())
			.isEqualTo("conversations=all sessions=all questions=all categories=all runs=1 recallLimit=10 judge=true");
		assertThat(BenchmarkConfig.parse("--conversations=2", "--sessions=3", "--no-judge").describeSubset())
			.startsWith("conversations=2 sessions=3 questions=all")
			.endsWith("judge=false");
	}

	@Test
	void unknownOrMalformedArgumentsFailFast() {
		// A typo'd subset flag would otherwise run for hours on the wrong slice.
		for (String bad : List.of("--convrsations=2", "--runs=0", "--runs=many", "conversations=2", "-c 2")) {
			assertThatThrownBy(() -> BenchmarkConfig.parse(bad))
				.as(bad)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("usage:");
		}
	}
}
