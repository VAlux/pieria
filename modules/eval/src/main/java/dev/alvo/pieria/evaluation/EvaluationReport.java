package dev.alvo.pieria.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The benchmark's output: plain records so the whole run serializes to one self-describing JSON file
 * and renders to HTML without a database, model provider, or daemon runtime.
 *
 * <p>The file carries the {@link BenchmarkConfig} and the provider/model identity that produced it,
 * so a report is reproducible from itself. Metrics are scored per question, aggregated per
 * conversation, per LoCoMo category, and once overall.
 */
public record EvaluationReport(
	Instant generatedAt,
	String benchmark,
	BenchmarkConfig config,
	Map<String, String> models,
	Summary summary,
	List<ConversationReport> conversations) {

	public EvaluationReport {
		// LinkedHashMap, not Map.copyOf: the model rows should render in the order they were recorded.
		models = models == null ? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(models));
		conversations = conversations == null ? List.of() : List.copyOf(conversations);
	}

	/**
	 * Run-wide aggregate. {@code byCategory} is keyed by LoCoMo question category (1 multi-hop,
	 * 2 temporal, 3 open-domain, 4 single-hop, 5 adversarial) — the breakdown that makes a subset
	 * run interpretable.
	 */
	public record Summary(
		int conversations,
		int questions,
		int memoriesStored,
		double answerFaithfulness,
		double retrievalHitRate,
		double meanReciprocalRank,
		Latency latency,
		Map<Integer, CategoryScore> byCategory) {

		public Summary {
			// TreeMap so categories always render 1..5 in order, whatever order they were scored in.
			byCategory = byCategory == null ? Map.of()
				: Collections.unmodifiableMap(new TreeMap<>(byCategory));
		}
	}

	public record CategoryScore(
		int questions,
		double answerFaithfulness,
		double retrievalHitRate,
		double meanReciprocalRank) {
	}

	public record ConversationReport(
		String name,
		int turns,
		int memoriesStored,
		double answerFaithfulness,
		double retrievalHitRate,
		double meanReciprocalRank,
		Latency latency,
		List<QueryReport> queries) {

		public ConversationReport {
			queries = queries == null ? List.of() : List.copyOf(queries);
		}
	}

	/**
	 * One question end to end: what was asked, what the daemon answered, whether the judge accepted
	 * it, and which memories came back in which order.
	 */
	public record QueryReport(
		String question,
		int category,
		String expectedAnswer,
		String actualAnswer,
		boolean answerFaithful,
		List<String> expectedEvidence,
		List<String> retrievedMemories,
		double hitRate,
		double reciprocalRank,
		long latencyMs) {

		public QueryReport {
			expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
			retrievedMemories = retrievedMemories == null ? List.of() : List.copyOf(retrievedMemories);
		}
	}

	public record Latency(long ingestionMs, long recallMs, long totalMs) {

		public static Latency of(long ingestionMs, long recallMs) {
			return new Latency(ingestionMs, recallMs, ingestionMs + recallMs);
		}
	}

	/** Every question in the run, flattened — the input to every aggregate below. */
	public static List<QueryReport> allQueries(List<ConversationReport> conversations) {
		List<QueryReport> queries = new ArrayList<>();
		for (ConversationReport conversation : conversations) {
			queries.addAll(conversation.queries());
		}
		return queries;
	}

	/** Scores a set of questions; {@code null}-safe and empty-safe (all zeros for no questions). */
	public static CategoryScore score(List<QueryReport> queries) {
		if (queries == null || queries.isEmpty()) {
			return new CategoryScore(0, 0.0, 0.0, 0.0);
		}
		double faithful = 0;
		double hitRate = 0;
		double reciprocalRank = 0;
		for (QueryReport query : queries) {
			faithful += query.answerFaithful() ? 1.0 : 0.0;
			hitRate += query.hitRate();
			reciprocalRank += query.reciprocalRank();
		}
		int n = queries.size();
		return new CategoryScore(n, faithful / n, hitRate / n, reciprocalRank / n);
	}

	/** Groups questions by LoCoMo category and scores each group. */
	public static Map<Integer, CategoryScore> scoreByCategory(List<QueryReport> queries) {
		Map<Integer, List<QueryReport>> grouped = new TreeMap<>();
		for (QueryReport query : queries) {
			grouped.computeIfAbsent(query.category(), key -> new ArrayList<>()).add(query);
		}
		Map<Integer, CategoryScore> scores = new LinkedHashMap<>();
		grouped.forEach((category, group) -> scores.put(category, score(group)));
		return scores;
	}
}
