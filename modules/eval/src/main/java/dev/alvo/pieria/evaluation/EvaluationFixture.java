package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Checked-in deterministic evaluation data: transcript, expected extraction output, expected
 * recall evidence, and the pinned answer for each recall query.
 */
public record EvaluationFixture(
	String name,
	String profileName,
	String sessionId,
	List<TranscriptMessage> transcript,
	List<ExpectedMemory> expectedMemories,
	List<RecallExpectation> recalls) {

	public EvaluationFixture {
		name = requireText(name, "name");
		profileName = requireText(profileName, "profileName");
		sessionId = requireText(sessionId, "sessionId");
		transcript = transcript == null ? List.of() : List.copyOf(transcript);
		expectedMemories = expectedMemories == null ? List.of() : List.copyOf(expectedMemories);
		recalls = recalls == null ? List.of() : List.copyOf(recalls);
	}

	public List<Message> toMessages() {
		return transcript.stream()
			.map(m -> Message.of(sessionId, m.role(), m.content()))
			.toList();
	}

	static String normalizedContent(String content) {
		return content == null ? "" : content.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	static String memoryKey(MemoryType type, String content, String topicKey) {
		return type.wire() + "\n" + normalizedContent(content) + "\n" + Objects.toString(topicKey, "");
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}

	public record TranscriptMessage(String role, String content) {
		public TranscriptMessage {
			role = requireText(role, "role");
			content = requireText(content, "content");
		}
	}

	public record ExpectedMemory(
		MemoryType type,
		String content,
		String topicKey,
		String payload) {

		public ExpectedMemory {
			type = Objects.requireNonNull(type, "type");
			content = requireText(content, "content");
			payload = payload == null ? "{}" : payload;
		}

		String key() {
			return memoryKey(type, content, topicKey);
		}
	}

	public record RecallExpectation(
		String query,
		List<String> expectedEvidence,
		String expectedAnswer) {

		public RecallExpectation {
			query = requireText(query, "query");
			expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
			expectedAnswer = requireText(expectedAnswer, "expectedAnswer");
		}
	}
}
