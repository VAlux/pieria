package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.evaluation.EvaluationReport.ConversationReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Latency;
import dev.alvo.pieria.evaluation.EvaluationReport.QueryReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Score;
import dev.alvo.pieria.evaluation.EvaluationReport.Spend;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.ModelGateway.AnswerVerdict;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scoring semantics, which are the whole reason the judge pass exists: an adversarial question
 * inverts what counts as success, and the funnel gates have to attribute a loss to the stage that
 * actually caused it.
 */
class JudgeRunnerTests {

	@Test
	void adversarialQuestionScoresCorrectOnlyWhenTheDaemonDeclines() {
		QueryReport declined = adversarial("I have no memory of that.");
		QueryReport tookTheBait = adversarial("She realized self-care is important.");

		Score score = score(new StubJudge(AnswerVerdict.ABSTAINED, always -> true), declined);
		Score fell = score(new StubJudge(AnswerVerdict.CORRECT, always -> true), tookTheBait);

		// Asserting the trap is the failure; the old harness scored it exactly backwards.
		assertThat(score.correct()).isEqualTo(1);
		assertThat(score.accuracy()).isEqualTo(1.0);
		assertThat(fell.correct()).isZero();
		assertThat(fell.wrong()).isEqualTo(1);
	}

	@Test
	void adversarialQuestionsAreExcludedFromBothFunnelGates() {
		Score score = score(new StubJudge(AnswerVerdict.ABSTAINED, always -> true),
			adversarial("I have no memory of that."));

		// There is no gold fact to find, so counting one would deflate coverage for free.
		assertThat(score.gatedQuestions()).isZero();
		assertThat(judged(new StubJudge(AnswerVerdict.ABSTAINED, always -> true),
			adversarial("I have no memory of that.")).factExtracted()).isNull();
	}

	@Test
	void aFactThatNeverSurvivedIngestionIsNotScoredAsARetrievalMiss() {
		// The judge says no candidate supports the answer — gate 1 fails.
		StubJudge judge = new StubJudge(AnswerVerdict.ABSTAINED, evidence -> false);

		QueryReport judgedQuery = judged(judge, answerable());
		Score score = EvaluationReport.score(List.of(judgedQuery));

		assertThat(judgedQuery.factExtracted()).isFalse();
		assertThat(judgedQuery.evidenceRetrieved()).isNull();
		assertThat(score.extractionCoverage()).isZero();
		// Retrieval is not blamed for a fact that was never there to retrieve.
		assertThat(score.retrievalRecall()).isZero();
		assertThat(judge.supportCalls).isEqualTo(1);
	}

	@Test
	void aStoredButUnretrievedFactIsScoredAsARetrievalMiss() {
		// Supported by the extraction shortlist, absent from the retrieved set.
		StubJudge judge = new StubJudge(AnswerVerdict.ABSTAINED, evidence -> evidence.contains("shortlisted"));

		QueryReport judgedQuery = judged(judge, answerable());
		Score score = EvaluationReport.score(List.of(judgedQuery));

		assertThat(judgedQuery.factExtracted()).isTrue();
		assertThat(judgedQuery.evidenceRetrieved()).isFalse();
		assertThat(score.extractionCoverage()).isEqualTo(1.0);
		assertThat(score.retrievalRecall()).isZero();
	}

	@Test
	void decliningWhereAnAnswerWasExpectedIsCountedApartFromAnsweringWrongly() {
		Score abstained = score(new StubJudge(AnswerVerdict.ABSTAINED, always -> true), answerable());
		Score wrong = score(new StubJudge(AnswerVerdict.WRONG, always -> true), answerable());

		assertThat(abstained.abstained()).isEqualTo(1);
		assertThat(abstained.hallucinationRate()).isZero();
		assertThat(wrong.wrong()).isEqualTo(1);
		assertThat(wrong.hallucinationRate()).isEqualTo(1.0);
	}

	private static QueryReport answerable() {
		return new QueryReport("What is the dog called?", 4, false, "Biscuit", "…", null, null, null,
			List.of("I adopted a dog named Biscuit"), List.of("retrieved memory"),
			List.of("shortlisted memory"), 10);
	}

	private static QueryReport adversarial(String answer) {
		return new QueryReport("What did Melanie realize?", 5, true, "self-care is important", answer,
			null, null, null, List.of("Running taught me self-care matters"), List.of(), List.of(), 10);
	}

	private static Score score(ModelGateway judge, QueryReport query) {
		return EvaluationReport.score(List.of(judged(judge, query)));
	}

	private static QueryReport judged(ModelGateway judge, QueryReport query) {
		ConversationReport conversation = new ConversationReport("conv-1", 2, 1,
			Score.EMPTY, Latency.of(1, 1), Spend.NONE, List.of(query), List.of("shortlisted memory"));
		return new JudgeRunner(judge).judge(List.of(conversation)).getFirst().queries().getFirst();
	}

	/** Fixed verdict, and a support predicate over the joined evidence, so gates can be driven apart. */
	private static final class StubJudge implements ModelGateway {

		private final AnswerVerdict verdict;
		private final Predicate<String> supported;
		private final List<String> seen = new ArrayList<>();
		private int supportCalls;

		private StubJudge(AnswerVerdict verdict, Predicate<String> supported) {
			this.verdict = verdict;
			this.supported = supported;
		}

		@Override
		public AnswerVerdict judgeAnswer(String question, String expectedAnswer, String actualAnswer) {
			return verdict;
		}

		@Override
		public boolean judgeEvidenceSupport(String question, String expectedAnswer, List<String> evidence) {
			supportCalls++;
			String joined = String.join(" ", evidence);
			seen.add(joined);
			return supported.test(joined);
		}

		@Override
		public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
			throw new UnsupportedOperationException("the judge pass never synthesizes");
		}

		@Override
		public float[] embed(String text) {
			throw new UnsupportedOperationException("the judge pass never embeds");
		}
	}
}
