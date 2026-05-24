package dev.alvo.pieria.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Local evaluation output. Metrics are intentionally plain records so reports can be written as
 * JSON without depending on a database, model provider, or daemon runtime.
 */
public record EvaluationReport(
	Instant generatedAt,
	List<FixtureReport> fixtures,
	Summary summary) {

	public EvaluationReport {
		fixtures = fixtures == null ? List.of() : List.copyOf(fixtures);
	}

	public record FixtureReport(
		String fixtureName,
		ExtractionReport extraction,
		List<RecallReport> recalls,
		double retrievalHitRate,
		double meanReciprocalRank,
		double answerFaithfulness,
		Latency latency,
		TokenUsage tokenUsage) {

		public FixtureReport {
			recalls = recalls == null ? List.of() : List.copyOf(recalls);
		}
	}

	public record ExtractionReport(
		int expectedMemories,
		int actualMemories,
		int truePositiveMemories,
		double precision,
		double recall) {
	}

	public record RecallReport(
		String query,
		List<String> expectedEvidence,
		List<String> actualEvidence,
		double hitRate,
		double reciprocalRank,
		boolean answerFaithful,
		String expectedAnswer,
		String actualAnswer,
		long latencyMs) {

		public RecallReport {
			expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
			actualEvidence = actualEvidence == null ? List.of() : List.copyOf(actualEvidence);
		}
	}

	public record Latency(
		long ingestionMs,
		long recallMs,
		long totalMs) {
	}

	public record TokenUsage(
		long promptTokens,
		long completionTokens,
		long totalTokens,
		Map<String, Integer> callsByStage) {

		public TokenUsage {
			callsByStage = callsByStage == null ? Map.of() : Map.copyOf(callsByStage);
		}

		static TokenUsage zero() {
			return new TokenUsage(0, 0, 0, Map.of());
		}
	}

	public record Summary(
		int fixtureCount,
		double extractionPrecision,
		double extractionRecall,
		double retrievalHitRate,
		double meanReciprocalRank,
		double answerFaithfulness,
		Latency latency,
		TokenUsage tokenUsage) {
	}
}
