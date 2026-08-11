package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.evaluation.EvaluationFixture.RecallExpectation;
import dev.alvo.pieria.evaluation.EvaluationFixture.TranscriptMessage;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoCoMoBenchmarkAdapterTests {

	private List<EvaluationFixture> parseSample(String... args) throws Exception {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		try (InputStream in = loader.getResourceAsStream("evaluation/benchmarks/locomo-sample.json")) {
			assertThat(in).as("sample resource present").isNotNull();
			return new LoCoMoBenchmarkAdapter().parse(in, BenchmarkConfig.parse(args));
		}
	}

	@Test
	void parsesConversationSessionsInOrderIntoTranscript() throws Exception {
		List<EvaluationFixture> fixtures = parseSample();

		assertThat(fixtures).extracting(EvaluationFixture::name).containsExactly("conv-1", "conv-2");
		EvaluationFixture fixture = fixtures.getFirst();
		assertThat(fixture.profileName()).isEqualTo("locomo-eval");
		assertThat(fixture.sessionId()).isEqualTo("locomo-conv-1");

		// Three sessions of two turns each, in ascending session order.
		assertThat(fixture.transcript()).hasSize(6);
		assertThat(fixture.transcript().get(0).role()).isEqualTo("user");
		assertThat(fixture.transcript().get(1).role()).isEqualTo("assistant");
		assertThat(fixture.transcript().get(2).content()).contains("Yosemite");
		// An image-only turn folds blip_caption into the text.
		assertThat(fixture.transcript().get(3).content()).contains("a photo of a dog on a mountain trail");
	}

	@Test
	void prefixesEveryTurnWithItsSessionDate() throws Exception {
		EvaluationFixture fixture = parseSample().getFirst();

		assertThat(fixture.transcript()).extracting(TranscriptMessage::content)
			.startsWith(
				"[1:56 pm on 8 May, 2023] Caroline: I just adopted a rescue dog named Biscuit last weekend!",
				"[1:56 pm on 8 May, 2023] Melanie: That's wonderful! What breed is Biscuit?");
		// Turns from the next session carry that session's own date.
		assertThat(fixture.transcript().get(4).content()).startsWith("[9:02 am on 2 June, 2023] Caroline: ");
	}

	@Test
	void stampsEachTurnWithItsSessionTimestamp() throws Exception {
		EvaluationFixture fixture = parseSample().getFirst();

		// Sent to the daemon as the message timestamp, so "yesterday" in a turn resolves against 2023
		// rather than the ingest wall clock.
		assertThat(fixture.transcript()).extracting(TranscriptMessage::timestamp).containsExactly(
			Instant.parse("2023-05-08T13:56:00Z"), Instant.parse("2023-05-08T13:56:00Z"),
			Instant.parse("2023-05-20T10:15:00Z"), Instant.parse("2023-05-20T10:15:00Z"),
			Instant.parse("2023-06-02T09:02:00Z"), Instant.parse("2023-06-02T09:02:00Z"));
	}

	@Test
	void mapsQaPairsToRecallExpectationsWithResolvedUndatedEvidence() throws Exception {
		EvaluationFixture fixture = parseSample().getFirst();

		assertThat(fixture.recalls()).hasSize(6);
		RecallExpectation first = fixture.recalls().getFirst();
		assertThat(first.query()).isEqualTo("What is the name of Caroline's dog?");
		assertThat(first.expectedAnswer()).isEqualTo("Biscuit");
		assertThat(first.category()).isEqualTo(4);
		// Evidence id "D1:1" resolves to the bare turn text — no date prefix, no speaker prefix — so
		// the harness's token-containment scoring is unaffected by how turns are ingested.
		assertThat(first.expectedEvidence())
			.containsExactly("I just adopted a rescue dog named Biscuit last weekend!");

		// Category 5 carries no "answer", only "adversarial_answer".
		RecallExpectation adversarial = fixture.recalls().getLast();
		assertThat(adversarial.category()).isEqualTo(5);
		assertThat(adversarial.expectedAnswer()).isEqualTo("not mentioned");
	}

	@Test
	void sessionCapTrimsTheTranscriptAndDropsUnanswerableQuestions() throws Exception {
		EvaluationFixture fixture = parseSample("--sessions=2").getFirst();

		assertThat(fixture.transcript()).hasSize(4);
		assertThat(fixture.transcript()).noneMatch(m -> m.content().contains("agility classes"));
		// The two questions whose evidence lives in session 3 are dropped with it.
		assertThat(fixture.recalls()).extracting(RecallExpectation::category).containsExactly(4, 2, 1, 3);
	}

	@Test
	void categoryFilterKeepsOnlyTheRequestedCategories() throws Exception {
		EvaluationFixture fixture = parseSample("--categories=2,4").getFirst();

		assertThat(fixture.recalls()).extracting(RecallExpectation::category).containsExactly(4, 2, 4);
	}

	@Test
	void questionLimitSamplesEvenlyRatherThanTakingTheHead() throws Exception {
		EvaluationFixture fixture = parseSample("--questions=2").getFirst();

		// Six questions, limit two → stride three → indices 0 and 3, spanning the category range.
		assertThat(fixture.recalls()).extracting(RecallExpectation::category).containsExactly(4, 3);
	}

	@Test
	void questionLimitAboveTheAvailableCountKeepsEverything() throws Exception {
		EvaluationFixture fixture = parseSample("--questions=50").getFirst();

		assertThat(fixture.recalls()).hasSize(6);
	}

	@Test
	void conversationCountTakesThePrefixOfTheDataset() throws Exception {
		assertThat(parseSample("--conversations=1")).extracting(EvaluationFixture::name)
			.containsExactly("conv-1");
	}

	@Test
	void conversationIdsSelectExplicitSamples() throws Exception {
		assertThat(parseSample("--conversations=conv-2")).extracting(EvaluationFixture::name)
			.containsExactly("conv-2");
	}

	@Test
	void unknownConversationIdFailsRatherThanSilentlyRunningADifferentSlice() {
		assertThatThrownBy(() -> parseSample("--conversations=conv-1,conv-99"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("conv-99");
	}
}
