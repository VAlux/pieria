package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.domain.Chunk;
import dev.alvo.pieria.domain.Classification;
import dev.alvo.pieria.domain.ExtractedCandidate;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.QueryAnalysis;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.domain.TemporalFact;
import dev.alvo.pieria.domain.VerificationResult;
import dev.alvo.pieria.domain.VerificationVerdict;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.retrieval.DeterministicQueryAnalyzer;

import java.util.ArrayList;
import java.util.List;

/**
 * Network-free, deterministic gateway for exercising the benchmark adapters end-to-end without a
 * pinned fixture (benchmark fixtures carry no gold extraction set). It turns each transcript message
 * into a {@code FACT}, passes verification, classifies with a content-derived topic key, analyses
 * queries via the deterministic analyzer, and synthesizes by echoing the top candidate.
 */
final class DeterministicBenchmarkGateway implements ModelGateway {

	private static final int EMBEDDING_DIMENSION = 1024;
	private final DeterministicQueryAnalyzer analyzer = new DeterministicQueryAnalyzer();

	@Override
	public List<Memory> extractMemories(List<Message> messages) {
		throw new UnsupportedOperationException("benchmark harness uses chunk extraction");
	}

	@Override
	public List<ExtractedCandidate> extract(Chunk chunk) {
		if (chunk == null) {
			return List.of();
		}
		List<ExtractedCandidate> candidates = new ArrayList<>();
		for (Message message : chunk.messages()) {
			if (message.content() != null && !message.content().isBlank()) {
				candidates.add(new ExtractedCandidate(message.content().strip(), MemoryType.FACT,
					chunk.index(), "benchmark"));
			}
		}
		return candidates;
	}

	@Override
	public List<ExtractedCandidate> extractDetail(Chunk chunk) {
		return List.of();
	}

	@Override
	public VerificationResult verify(ExtractedCandidate candidate, String transcript) {
		String content = candidate == null ? "" : candidate.content();
		return new VerificationResult(VerificationVerdict.PASS, content, "benchmark pass-through");
	}

	@Override
	public Classification classify(String content) {
		return new Classification(MemoryType.FACT, topicKey(content), List.of(), "{}");
	}

	@Override
	public QueryAnalysis analyzeQuery(String query) {
		QueryAnalysis fallback = analyzer.analyze(query);
		String hyde = query == null || query.isBlank() ? null : "answer: " + query.strip();
		return new QueryAnalysis(fallback.topicKeys(), fallback.ftsTerms(), hyde);
	}

	@Override
	public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
		return synthesizeRecall(query, candidates, List.of());
	}

	@Override
	public String synthesizeRecall(String query, List<RecallCandidate> candidates, List<TemporalFact> temporalFacts) {
		if (candidates == null || candidates.isEmpty()) {
			return "No evidence found.";
		}
		return candidates.getFirst().memory().content();
	}

	@Override
	public float[] embed(String text) {
		float[] vector = new float[EMBEDDING_DIMENSION];
		int hash = text == null ? 0 : text.hashCode();
		for (int i = 0; i < vector.length; i++) {
			vector[i] = ((hash + i) % 11) / 11.0f;
		}
		return vector;
	}

	private static String topicKey(String content) {
		String normalized = content == null ? "" : content.toLowerCase(java.util.Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-");
		if (normalized.length() > 40) {
			normalized = normalized.substring(0, 40);
		}
		return "benchmark." + normalized;
	}
}
