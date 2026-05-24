package dev.alvo.pieria.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;

final class EvaluationTokenUsage {

	private long promptTokens;
	private long completionTokens;
	private final Map<String, Integer> callsByStage = new LinkedHashMap<>();

	synchronized void record(String stage, String prompt, String completion) {
		promptTokens += countTokens(prompt);
		completionTokens += countTokens(completion);
		callsByStage.merge(stage, 1, Integer::sum);
	}

	synchronized EvaluationReport.TokenUsage snapshot() {
		return new EvaluationReport.TokenUsage(
			promptTokens,
			completionTokens,
			promptTokens + completionTokens,
			Map.copyOf(callsByStage));
	}

	private static int countTokens(String value) {
		if (value == null || value.isBlank()) {
			return 0;
		}
		return value.strip().split("\\s+").length;
	}
}
