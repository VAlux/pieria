package dev.alvo.pieria.evaluation;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoCoMoBenchmarkAdapterTests {

	private List<EvaluationFixture> parseSample() throws Exception {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		try (InputStream in = loader.getResourceAsStream("evaluation/benchmarks/locomo-sample.json")) {
			assertThat(in).as("sample resource present").isNotNull();
			return new LoCoMoBenchmarkAdapter().parse(in);
		}
	}

	@Test
	void parsesConversationSessionsInOrderIntoTranscript() throws Exception {
		List<EvaluationFixture> fixtures = parseSample();

		assertThat(fixtures).hasSize(1);
		EvaluationFixture fixture = fixtures.getFirst();
		assertThat(fixture.name()).isEqualTo("conv-1");
		assertThat(fixture.profileName()).isEqualTo("locomo-eval");

		// session_1 (2 turns) then session_2 (1 text turn + 1 image-caption turn) in ascending order.
		assertThat(fixture.transcript()).hasSize(4);
		assertThat(fixture.transcript().get(0).role()).isEqualTo("user");
		assertThat(fixture.transcript().get(0).content()).isEqualTo("Caroline: I just adopted a rescue dog named Biscuit last weekend!");
		assertThat(fixture.transcript().get(1).role()).isEqualTo("assistant");
		assertThat(fixture.transcript().get(2).content()).contains("Yosemite");
		// image-only turn folds blip_caption into text.
		assertThat(fixture.transcript().get(3).content()).contains("a photo of a dog on a mountain trail");
	}

	@Test
	void mapsQaPairsToRecallExpectationsWithEvidence() throws Exception {
		EvaluationFixture fixture = parseSample().getFirst();

		assertThat(fixture.recalls()).hasSize(2);
		EvaluationFixture.RecallExpectation first = fixture.recalls().getFirst();
		assertThat(first.query()).isEqualTo("What is the name of Caroline's dog?");
		assertThat(first.expectedAnswer()).isEqualTo("Biscuit");
		// Evidence id "D1:1" is resolved to the referenced turn text (speaker prefix excluded).
		assertThat(first.expectedEvidence())
			.containsExactly("I just adopted a rescue dog named Biscuit last weekend!");

		EvaluationFixture.RecallExpectation second = fixture.recalls().get(1);
		assertThat(second.expectedEvidence())
			.containsExactly("Biscuit is a beagle mix. We went hiking in Yosemite this weekend.");

		// LoCoMo supplies no gold extraction set.
		assertThat(fixture.expectedMemories()).isEmpty();
	}
}
