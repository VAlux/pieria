package dev.alvo.pieria.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationRunnerTests {

	@TempDir
	Path tempDir;

	@Test
	void runsDeterministicFixtureHarnessWithoutNetworkModels() throws Exception {
		EvaluationFixture fixture = new EvaluationFixtureLoader()
			.loadResource("evaluation/fixtures/project-preferences.json");

		EvaluationReport report = new EvaluationRunner().run(List.of(fixture));

		assertThat(report.fixtures()).hasSize(1);
		EvaluationReport.FixtureReport fixtureReport = report.fixtures().getFirst();
		assertThat(fixtureReport.extraction().precision()).isEqualTo(1.0);
		assertThat(fixtureReport.extraction().recall()).isEqualTo(1.0);
		assertThat(fixtureReport.retrievalHitRate()).isEqualTo(1.0);
		assertThat(fixtureReport.meanReciprocalRank()).isEqualTo(1.0);
		assertThat(fixtureReport.answerFaithfulness()).isEqualTo(1.0);
		assertThat(fixtureReport.tokenUsage().totalTokens()).isPositive();
		assertThat(fixtureReport.tokenUsage().callsByStage()).containsKeys(
			"extract", "verify", "classify", "analyzeQuery", "synthesizeRecall");
		assertThat(fixtureReport.latency().totalMs()).isGreaterThanOrEqualTo(0);
	}

	@Test
	void writesReportJsonToLocalOutputDirectory() throws Exception {
		EvaluationFixtureLoader loader = new EvaluationFixtureLoader();
		EvaluationReport report = new EvaluationRunner().run(List.of(
			loader.loadResource("evaluation/fixtures/project-preferences.json"),
			loader.loadResource("evaluation/fixtures/release-facts.json")));

		Path written = new EvaluationReportWriter().write(report, tempDir);

		assertThat(written).exists();
		assertThat(written.getParent()).isEqualTo(tempDir);
		String json = Files.readString(written);
		assertThat(json).contains("\"fixtureCount\" : 2");
		assertThat(json).contains("\"retrievalHitRate\" : 1.0");
		assertThat(report.summary().tokenUsage().totalTokens()).isPositive();
	}
}
