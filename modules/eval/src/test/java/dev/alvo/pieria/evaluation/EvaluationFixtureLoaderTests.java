package dev.alvo.pieria.evaluation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationFixtureLoaderTests {

	@Test
	void loadsFixtureWithExpectedMemoriesAndRecallCases() throws Exception {
		EvaluationFixture fixture = new EvaluationFixtureLoader()
			.loadResource("evaluation/fixtures/project-preferences.json");

		assertThat(fixture.name()).isEqualTo("project-preferences");
		assertThat(fixture.toMessages()).hasSize(3);
		assertThat(fixture.expectedMemories()).hasSize(2);
		assertThat(fixture.recalls()).hasSize(2);
		assertThat(fixture.expectedMemories().getFirst().topicKey()).isEqualTo("preference.package-manager");
	}
}
