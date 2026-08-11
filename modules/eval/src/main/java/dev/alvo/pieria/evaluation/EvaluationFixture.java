package dev.alvo.pieria.evaluation;

import java.time.Instant;
import java.util.List;

/**
 * One LoCoMo conversation prepared for the harness: the transcript to ingest and the questions to
 * recall, each with its gold answer and the evidence turns that answer it.
 */
public record EvaluationFixture(
	String name,
	String profileName,
	String sessionId,
	List<TranscriptMessage> transcript,
	List<RecallExpectation> recalls) {

	public EvaluationFixture {
		name = requireText(name, "name");
		profileName = requireText(profileName, "profileName");
		sessionId = requireText(sessionId, "sessionId");
		transcript = transcript == null ? List.of() : List.copyOf(transcript);
		recalls = recalls == null ? List.of() : List.copyOf(recalls);
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}

	/**
	 * One turn. {@code timestamp} is the session's date-time, sent to the daemon so relative dates in
	 * the turn ("yesterday") resolve against when the conversation happened rather than the ingest.
	 * Null when the dataset gave no date for the session.
	 */
	public record TranscriptMessage(String role, String content, Instant timestamp) {
		public TranscriptMessage {
			role = requireText(role, "role");
			content = requireText(content, "content");
		}
	}

	/**
	 * One question. {@code category} is the LoCoMo question category (1 multi-hop, 2 temporal,
	 * 3 open-domain, 4 single-hop, 5 adversarial), carried through so the report can break the score
	 * down by reasoning type.
	 */
	public record RecallExpectation(
		String query,
		List<String> expectedEvidence,
		String expectedAnswer,
		int category) {

		public RecallExpectation {
			query = requireText(query, "query");
			expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
			expectedAnswer = requireText(expectedAnswer, "expectedAnswer");
		}
	}
}
