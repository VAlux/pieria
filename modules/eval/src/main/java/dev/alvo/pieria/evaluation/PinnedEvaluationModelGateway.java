package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.domain.Chunk;
import dev.alvo.pieria.domain.Classification;
import dev.alvo.pieria.domain.ExtractedCandidate;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.QueryAnalysis;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.domain.TemporalFact;
import dev.alvo.pieria.domain.VerificationResult;
import dev.alvo.pieria.domain.VerificationVerdict;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.retrieval.DeterministicQueryAnalyzer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fixture-pinned model gateway for local evaluation. It supplies deterministic extraction,
 * classification, query analysis, synthesis, embeddings, and token accounting without provider
 * calls.
 */
final class PinnedEvaluationModelGateway implements ModelGateway {

	private static final int EMBEDDING_DIMENSION = 1024;

	private final EvaluationFixture fixture;
	private final EvaluationTokenUsage tokenUsage;
	private final DeterministicQueryAnalyzer fallbackAnalyzer = new DeterministicQueryAnalyzer();
	private final Map<String, EvaluationFixture.ExpectedMemory> expectedByContent = new LinkedHashMap<>();
	private final Map<String, EvaluationFixture.RecallExpectation> recallByQuery = new LinkedHashMap<>();

	PinnedEvaluationModelGateway(EvaluationFixture fixture, EvaluationTokenUsage tokenUsage) {
		this.fixture = Objects.requireNonNull(fixture, "fixture");
		this.tokenUsage = Objects.requireNonNull(tokenUsage, "tokenUsage");
		for (EvaluationFixture.ExpectedMemory memory : fixture.expectedMemories()) {
			expectedByContent.put(EvaluationFixture.normalizedContent(memory.content()), memory);
		}
		for (EvaluationFixture.RecallExpectation recall : fixture.recalls()) {
			recallByQuery.put(recall.query(), recall);
		}
	}

	@Override
	public List<Memory> extractMemories(List<dev.alvo.pieria.domain.Message> messages) {
		throw new UnsupportedOperationException("fixture evaluation uses chunk extraction");
	}

	@Override
	public List<ExtractedCandidate> extract(Chunk chunk) {
		String transcript = chunk == null ? "" : chunk.transcript();
		String completion = String.join("\n", fixture.expectedMemories().stream()
			.map(EvaluationFixture.ExpectedMemory::content)
			.toList());
		tokenUsage.record("extract", transcript, completion);
		if (chunk == null || chunk.index() != 0) {
			return List.of();
		}
		return fixture.expectedMemories().stream()
			.map(memory -> new ExtractedCandidate(memory.content(), memory.type(), chunk.index(), fixture.name()))
			.toList();
	}

	@Override
	public List<ExtractedCandidate> extractDetail(Chunk chunk) {
		tokenUsage.record("extractDetail", chunk == null ? "" : chunk.transcript(), "");
		return List.of();
	}

	@Override
	public VerificationResult verify(ExtractedCandidate candidate, String transcript) {
		String content = candidate == null ? "" : candidate.content();
		boolean expected = expectedByContent.containsKey(EvaluationFixture.normalizedContent(content));
		tokenUsage.record("verify", transcript + "\n" + content, expected ? "PASS" : "DROP");
		if (!expected) {
			return new VerificationResult(VerificationVerdict.DROP, "", "not pinned in fixture");
		}
		return new VerificationResult(VerificationVerdict.PASS, content, "pinned fixture memory");
	}

	@Override
	public Classification classify(String content) {
		EvaluationFixture.ExpectedMemory expected =
			expectedByContent.get(EvaluationFixture.normalizedContent(content));
		if (expected == null) {
			throw new IllegalArgumentException("fixture has no classification for memory: " + content);
		}
		List<String> queries = List.of(
			"what about " + firstToken(expected.content()),
			"recall " + firstToken(expected.content()),
			"details for " + firstToken(expected.content()));
		tokenUsage.record("classify", content, expected.type().wire() + " " + Objects.toString(expected.topicKey(), ""));
		return new Classification(expected.type(), expected.topicKey(), queries, expected.payload());
	}

	@Override
	public QueryAnalysis analyzeQuery(String query) {
		QueryAnalysis fallback = fallbackAnalyzer.analyze(query);
		List<String> topicKeys = new ArrayList<>();
		EvaluationFixture.RecallExpectation recall = recallByQuery.get(query);
		if (recall != null) {
			for (String evidence : recall.expectedEvidence()) {
				EvaluationFixture.ExpectedMemory expected =
					expectedByContent.get(EvaluationFixture.normalizedContent(evidence));
				if (expected != null && expected.topicKey() != null && !topicKeys.contains(expected.topicKey())) {
					topicKeys.add(expected.topicKey());
				}
			}
		}
		for (String key : fallback.topicKeys()) {
			if (!topicKeys.contains(key)) {
				topicKeys.add(key);
			}
		}
		String hyde = query == null || query.isBlank() ? null : "answer: " + query.strip();
		tokenUsage.record("analyzeQuery", query, String.join(" ", topicKeys));
		return new QueryAnalysis(topicKeys, fallback.ftsTerms(), hyde);
	}

	@Override
	public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
		return synthesizeRecall(query, candidates, List.of());
	}

	@Override
	public String synthesizeRecall(String query, List<RecallCandidate> candidates, List<TemporalFact> temporalFacts) {
		EvaluationFixture.RecallExpectation recall = recallByQuery.get(query);
		String answer = recall == null ? "Insufficient fixture evidence." : recall.expectedAnswer();
		String prompt = query + "\n" + (candidates == null ? "" : candidates.stream()
			.map(candidate -> candidate.memory().content())
			.reduce("", (left, right) -> left + "\n" + right));
		tokenUsage.record("synthesizeRecall", prompt, answer);
		return answer;
	}

	@Override
	public float[] embed(String text) {
		tokenUsage.record("embed", text, "");
		float[] vector = new float[EMBEDDING_DIMENSION];
		int hash = text == null ? 0 : text.hashCode();
		for (int i = 0; i < vector.length; i++) {
			vector[i] = ((hash + i) % 11) / 11.0f;
		}
		return vector;
	}

	private static String firstToken(String content) {
		String[] parts = content.strip().split("[^A-Za-z0-9]+");
		return parts.length == 0 || parts[0].isBlank() ? "memory" : parts[0].toLowerCase();
	}
}
