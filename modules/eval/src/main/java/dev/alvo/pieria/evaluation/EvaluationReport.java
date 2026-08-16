package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.model.ModelGateway.AnswerVerdict;

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
 *
 * <h2>The pipeline is scored as a funnel, not a scalar</h2>
 * A single accuracy number cannot say <em>where</em> a question was lost, so every question is scored
 * at three gates:
 * <ol>
 *   <li><strong>extracted</strong> — did the gold fact survive ingestion into any stored memory?</li>
 *   <li><strong>retrieved</strong> — did recall surface a memory carrying it?</li>
 *   <li><strong>answered</strong> — did synthesis state it?</li>
 * </ol>
 * Without the split, an extraction policy that deliberately discards conversational detail is
 * indistinguishable from a ranking regression. Gates 1 and 2 do not apply to abstention-expected
 * (adversarial) questions, which have no gold fact to find; those are excluded from both rates.
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
		Score score,
		Latency latency,
		/** What the daemon under test spent: ingestion, retrieval and synthesis. */
		Spend pipelineSpend,
		/** What scoring the run cost — separate because it is a large, optional share of the bill. */
		Spend judgeSpend,
		/** {@code pipelineSpend + judgeSpend}: what the whole run cost. */
		Spend spend,
		Map<Integer, Score> byCategory) {

		public Summary {
			// TreeMap so categories always render 1..5 in order, whatever order they were scored in.
			byCategory = byCategory == null ? Map.of()
				: Collections.unmodifiableMap(new TreeMap<>(byCategory));
		}
	}

	/**
	 * Scores for one set of questions.
	 *
	 * @param questions          how many questions the score covers
	 * @param correct            answered as expected (for adversarial questions: correctly declined)
	 * @param wrong              asserted something other than the expected answer
	 * @param abstained          declined to answer where an answer was expected
	 * @param unjudged           no verdict yet — the judge pass was skipped
	 * @param accuracy           {@code correct / questions} — the north-star number
	 * @param hallucinationRate  {@code wrong / questions} — asserting beyond what was remembered
	 * @param abstentionRate     {@code abstained / questions} — the honest-failure rate
	 * @param gatedQuestions     questions with a gold fact to find (everything but adversarial)
	 * @param extractionCoverage of those, the fraction whose gold fact survived ingestion
	 * @param retrievalRecall    of the extracted ones, the fraction recall actually surfaced
	 * @param synthesisAccuracy  of the retrieved ones, the fraction synthesis then answered correctly
	 */
	public record Score(
		int questions,
		int correct,
		int wrong,
		int abstained,
		int unjudged,
		double accuracy,
		double hallucinationRate,
		double abstentionRate,
		int gatedQuestions,
		double extractionCoverage,
		double retrievalRecall,
		double synthesisAccuracy) {

		public static final Score EMPTY =
			new Score(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}

	public record ConversationReport(
		String name,
		int turns,
		int memoriesStored,
		Score score,
		Latency latency,
		/** This conversation's share of the pipeline spend, read from the daemon after its recalls. */
		Spend spend,
		List<QueryReport> queries,
		/**
		 * Every memory the conversation's ingest produced, kept so the extraction gate can be
		 * (re-)judged from a written report without re-running the expensive end-to-end pass.
		 */
		List<String> storedMemoryTexts) {

		public ConversationReport {
			spend = spend == null ? Spend.NONE : spend;
			queries = queries == null ? List.of() : List.copyOf(queries);
			storedMemoryTexts = storedMemoryTexts == null ? List.of() : List.copyOf(storedMemoryTexts);
		}
	}

	/**
	 * One question end to end: what was asked, what the daemon answered, how the judge scored it, and
	 * which memories came back in which order.
	 *
	 * @param expectAbstention  the question is adversarial — declining is the correct answer, and the
	 *                          {@code expectedAnswer} is the trap it baits
	 * @param verdict           the judge's three-way call, or {@code null} until the judge pass runs
	 * @param factExtracted     gate 1, or {@code null} when not applicable (adversarial) or unjudged
	 * @param evidenceRetrieved gate 2, same nullability
	 * @param extractionCandidates the stored memories shortlisted for the gate-1 judgement, recorded
	 *                          so a surprising verdict can be inspected without re-running the run
	 */
	public record QueryReport(
		String question,
		int category,
		boolean expectAbstention,
		String expectedAnswer,
		String actualAnswer,
		AnswerVerdict verdict,
		Boolean factExtracted,
		Boolean evidenceRetrieved,
		List<String> expectedEvidence,
		List<String> retrievedMemories,
		List<String> extractionCandidates,
		long latencyMs) {

		public QueryReport {
			expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
			retrievedMemories = retrievedMemories == null ? List.of() : List.copyOf(retrievedMemories);
			extractionCandidates = extractionCandidates == null ? List.of() : List.copyOf(extractionCandidates);
		}

		/** True when the run scored this question as it should have been answered. */
		public boolean correct() {
			return expectAbstention
				? verdict == AnswerVerdict.ABSTAINED
				: verdict == AnswerVerdict.CORRECT;
		}
	}

	public record Latency(long ingestionMs, long recallMs, long totalMs) {

		public static Latency of(long ingestionMs, long recallMs) {
			return new Latency(ingestionMs, recallMs, ingestionMs + recallMs);
		}
	}

	/**
	 * What a run cost in provider tokens, by model tier.
	 *
	 * <p>Recorded because the benchmark is no longer free to run: against a hosted provider a full
	 * pass is real money, and the two obvious levers (judging on or off, which model judges) move the
	 * bill several-fold. A cost that only exists as arithmetic in someone's head gets guessed at
	 * wrong, so it belongs in the report next to the numbers it bought.
	 *
	 * <p>{@code costUsd} is zero unless per-tier prices are configured
	 * ({@code pieria.stats.spend.<tier>.input-price}); {@code priced} says which it is, so a zero is
	 * never mistaken for a free run. Token counts are provider-reported and always present.
	 */
	public record Spend(
		List<TierSpend> tiers,
		long promptTokens,
		long completionTokens,
		double costUsd,
		boolean priced) {

		public static final Spend NONE = new Spend(List.of(), 0, 0, 0.0, false);

		public Spend {
			tiers = tiers == null ? List.of() : List.copyOf(tiers);
		}

		public record TierSpend(String tier, long calls, long promptTokens, long completionTokens,
		                        double costUsd) {
		}

		/** Combines several spends, merging same-named tiers. */
		public static Spend sum(List<Spend> spends) {
			Map<String, TierSpend> byTier = new LinkedHashMap<>();
			boolean priced = false;
			for (Spend spend : spends) {
				if (spend == null) {
					continue;
				}
				priced |= spend.priced();
				for (TierSpend tier : spend.tiers()) {
					byTier.merge(tier.tier(), tier, (a, b) -> new TierSpend(a.tier(),
						a.calls() + b.calls(),
						a.promptTokens() + b.promptTokens(),
						a.completionTokens() + b.completionTokens(),
						a.costUsd() + b.costUsd()));
				}
			}
			long prompt = 0;
			long completion = 0;
			double cost = 0;
			for (TierSpend tier : byTier.values()) {
				prompt += tier.promptTokens();
				completion += tier.completionTokens();
				cost += tier.costUsd();
			}
			return new Spend(List.copyOf(byTier.values()), prompt, completion, cost, priced);
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
	public static Score score(List<QueryReport> queries) {
		if (queries == null || queries.isEmpty()) {
			return Score.EMPTY;
		}
		int correct = 0;
		int wrong = 0;
		int abstained = 0;
		int unjudged = 0;
		int gated = 0;
		int extracted = 0;
		int retrieved = 0;
		int answeredAfterRetrieval = 0;
		for (QueryReport query : queries) {
			if (query.verdict() == null) {
				unjudged++;
			} else if (query.correct()) {
				correct++;
			} else if (query.verdict() == AnswerVerdict.ABSTAINED) {
				abstained++;
			} else {
				// Includes an adversarial question answered with the trap: asserting it is the failure.
				wrong++;
			}

			if (query.expectAbstention()) {
				continue;
			}
			gated++;
			if (Boolean.TRUE.equals(query.factExtracted())) {
				extracted++;
				if (Boolean.TRUE.equals(query.evidenceRetrieved())) {
					retrieved++;
					if (query.correct()) {
						answeredAfterRetrieval++;
					}
				}
			}
		}
		int n = queries.size();
		return new Score(
			n, correct, wrong, abstained, unjudged,
			ratio(correct, n), ratio(wrong, n), ratio(abstained, n),
			gated, ratio(extracted, gated), ratio(retrieved, extracted),
			ratio(answeredAfterRetrieval, retrieved));
	}

	/** Groups questions by LoCoMo category and scores each group. */
	public static Map<Integer, Score> scoreByCategory(List<QueryReport> queries) {
		Map<Integer, List<QueryReport>> grouped = new TreeMap<>();
		for (QueryReport query : queries) {
			grouped.computeIfAbsent(query.category(), key -> new ArrayList<>()).add(query);
		}
		Map<Integer, Score> scores = new LinkedHashMap<>();
		grouped.forEach((category, group) -> scores.put(category, score(group)));
		return scores;
	}

	/** Conditional rates have an empty denominator whenever the previous gate let nothing through. */
	private static double ratio(int numerator, int denominator) {
		return denominator == 0 ? 0.0 : (double) numerator / denominator;
	}
}
