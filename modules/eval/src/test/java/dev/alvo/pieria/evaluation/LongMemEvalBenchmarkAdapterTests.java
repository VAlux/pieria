package dev.alvo.pieria.evaluation;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LongMemEvalBenchmarkAdapterTests {

	private List<EvaluationFixture> parseSample() throws Exception {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		try (InputStream in = loader.getResourceAsStream("evaluation/benchmarks/longmemeval-sample.json")) {
			assertThat(in).as("sample resource present").isNotNull();
			return new LongMemEvalBenchmarkAdapter().parse(in);
		}
	}

	@Test
	void flattensHaystackSessionsAndMapsRoles() throws Exception {
		List<EvaluationFixture> fixtures = parseSample();

		assertThat(fixtures).hasSize(2);
		EvaluationFixture first = fixtures.getFirst();
		assertThat(first.name()).isEqualTo("q-singlehop-1");
		assertThat(first.profileName()).isEqualTo("longmemeval-eval");
		// 2 sessions x 2 turns flattened in array order.
		assertThat(first.transcript()).hasSize(4);
		assertThat(first.transcript().get(0).role()).isEqualTo("user");
		assertThat(first.transcript().get(1).role()).isEqualTo("assistant");
		assertThat(first.transcript().get(2).content()).contains("learning Rust");
	}

	@Test
	void recordsHasAnswerTurnAsEvidence() throws Exception {
		EvaluationFixture first = parseSample().getFirst();

		assertThat(first.recalls()).hasSize(1);
		EvaluationFixture.RecallExpectation recall = first.recalls().getFirst();
		assertThat(recall.query()).isEqualTo("What programming language did I say I was learning?");
		assertThat(recall.expectedAnswer()).isEqualTo("Rust");
		assertThat(recall.expectedEvidence()).containsExactly("I started learning Rust this week.");
		assertThat(first.expectedMemories()).isEmpty();
	}

	@Test
	void parsesAbstentionItemWithSpeakerTextFields() throws Exception {
		EvaluationFixture abstention = parseSample().get(1);

		assertThat(abstention.name()).isEqualTo("q-abstention-1_abs");
		// speaker/text fallback fields are honoured.
		assertThat(abstention.transcript()).hasSize(2);
		assertThat(abstention.transcript().getFirst().content()).contains("weather");
		EvaluationFixture.RecallExpectation recall = abstention.recalls().getFirst();
		assertThat(recall.expectedAnswer()).contains("don't have any information");
		// answer_session_ids empty and no has_answer flags ⇒ no evidence.
		assertThat(recall.expectedEvidence()).isEmpty();
	}
}
