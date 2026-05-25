package dev.alvo.pieria.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parses the real <a href="https://github.com/xiaowu0162/LongMemEval">LongMemEval</a> dataset
 * ({@code longmemeval_s.json} / {@code longmemeval_m.json} / {@code longmemeval_oracle.json}) into
 * {@link EvaluationFixture}s.
 *
 * <h2>Encoded schema assumptions (defensive — missing/extra fields are tolerated)</h2>
 * The on-disk file is a JSON <em>array</em> of question items. Each item object is assumed to
 * contain:
 * <ul>
 *   <li>{@code question_id} — string id (defaults to {@code longmemeval-<index>}); used as the
 *       fixture name and ingest session id. Ids ending in {@code _abs} mark abstention questions
 *       whose gold answer asserts the information is absent — we keep the provided answer verbatim.</li>
 *   <li>{@code question} — the recall query.</li>
 *   <li>{@code answer} — gold answer; coerced to a string (numeric/boolean stringified).</li>
 *   <li>{@code haystack_sessions} — array of sessions; each session is an array of turn objects with
 *       {@code role} and {@code content} (alternatives {@code speaker}/{@code text} tolerated). All
 *       turns across all sessions are flattened, in array order, into the ingest transcript. A turn
 *       may carry {@code has_answer: true} marking it as gold evidence; we record the
 *       {@code content} of such turns as expected recall evidence.</li>
 *   <li>{@code haystack_session_ids} / {@code answer_session_ids} — session id lists. When per-turn
 *       {@code has_answer} flags are absent, we fall back to treating every turn inside a session
 *       whose id appears in {@code answer_session_ids} as evidence (positional alignment with
 *       {@code haystack_session_ids}).</li>
 * </ul>
 *
 * <p>The {@code role} values in LongMemEval are already {@code user}/{@code assistant}, so they map
 * directly to the daemon's message roles; unrecognized roles default to {@code user}. Expected
 * memories are left empty (LongMemEval supplies no gold extraction set), so extraction metrics are
 * vacuous for this benchmark and only retrieval/answer metrics are meaningful.
 */
public final class LongMemEvalBenchmarkAdapter {

	private static final String PROFILE = "longmemeval-eval";

	private final ObjectMapper objectMapper;

	public LongMemEvalBenchmarkAdapter(ObjectMapper objectMapper) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
	}

	public LongMemEvalBenchmarkAdapter() {
		this(new ObjectMapper());
	}

	public List<EvaluationFixture> load(Path datasetFile) throws IOException {
		try (InputStream in = Files.newInputStream(datasetFile)) {
			return parse(in);
		}
	}

	public List<EvaluationFixture> parse(InputStream in) throws IOException {
		return parse(objectMapper.readTree(in));
	}

	public List<EvaluationFixture> parse(JsonNode root) {
		List<EvaluationFixture> fixtures = new ArrayList<>();
		if (root == null) {
			return fixtures;
		}
		Iterable<JsonNode> items = root.isArray() ? root : List.of(root);
		int index = 0;
		for (JsonNode item : items) {
			index++;
			EvaluationFixture fixture = parseItem(item, index);
			if (fixture != null) {
				fixtures.add(fixture);
			}
		}
		return fixtures;
	}

	private EvaluationFixture parseItem(JsonNode item, int index) {
		if (item == null || !item.isObject()) {
			return null;
		}
		String questionId = text(item, "question_id", "id");
		String name = questionId.isBlank() ? "longmemeval-" + index : questionId;
		String sessionId = "longmemeval-" + name;
		String question = text(item, "question", "query");
		String answer = stringValue(item.get("answer"));
		if (question.isBlank() || answer.isBlank()) {
			return null;
		}

		List<String> answerSessionIds = stringList(item.get("answer_session_ids"));
		List<String> haystackSessionIds = stringList(item.get("haystack_session_ids"));

		List<EvaluationFixture.TranscriptMessage> transcript = new ArrayList<>();
		List<String> evidence = new ArrayList<>();

		JsonNode sessions = item.get("haystack_sessions");
		if (sessions != null && sessions.isArray()) {
			int sessionIndex = 0;
			for (JsonNode session : sessions) {
				String sid = sessionIndex < haystackSessionIds.size() ? haystackSessionIds.get(sessionIndex) : null;
				boolean evidenceSession = sid != null && answerSessionIds.contains(sid);
				// Prefer per-turn has_answer flags; only fall back to whole-session evidence when this
				// evidence session has no flagged turns at all.
				boolean sessionHasFlags = sessionHasAnswerFlags(session);
				boolean wholeSessionEvidence = evidenceSession && !sessionHasFlags;
				if (session != null && session.isArray()) {
					for (JsonNode turn : session) {
						parseTurn(turn, wholeSessionEvidence, transcript, evidence);
					}
				}
				sessionIndex++;
			}
		}

		if (transcript.isEmpty()) {
			return null;
		}
		EvaluationFixture.RecallExpectation recall =
			new EvaluationFixture.RecallExpectation(question, evidence, answer);
		return new EvaluationFixture(name, PROFILE, sessionId, transcript, List.of(), List.of(recall));
	}

	private void parseTurn(JsonNode turn,
	                       boolean evidenceSession,
	                       List<EvaluationFixture.TranscriptMessage> transcript,
	                       List<String> evidence) {
		if (turn == null || !turn.isObject()) {
			return;
		}
		String content = text(turn, "content", "text", "value");
		if (content.isBlank()) {
			return;
		}
		String role = normalizeRole(text(turn, "role", "speaker"));
		transcript.add(new EvaluationFixture.TranscriptMessage(role, content));

		boolean hasAnswerFlag = turn.path("has_answer").asBoolean(false);
		if ((hasAnswerFlag || evidenceSession) && !evidence.contains(content)) {
			evidence.add(content);
		}
	}

	private static boolean sessionHasAnswerFlags(JsonNode session) {
		if (session == null || !session.isArray()) {
			return false;
		}
		for (JsonNode turn : session) {
			if (turn != null && turn.path("has_answer").asBoolean(false)) {
				return true;
			}
		}
		return false;
	}

	private static String normalizeRole(String role) {
		if (role == null) {
			return "user";
		}
		String lower = role.strip().toLowerCase(java.util.Locale.ROOT);
		return lower.equals("assistant") || lower.equals("bot") || lower.equals("system") ? "assistant" : "user";
	}

	private static String text(JsonNode node, String... fields) {
		if (node == null) {
			return "";
		}
		for (String field : fields) {
			JsonNode value = node.get(field);
			if (value != null && value.isTextual() && !value.asText().isBlank()) {
				return value.asText().strip();
			}
		}
		return "";
	}

	private static String stringValue(JsonNode node) {
		if (node == null || node.isNull()) {
			return "";
		}
		if (node.isTextual()) {
			return node.asText().strip();
		}
		if (node.isArray()) {
			List<String> parts = new ArrayList<>();
			for (JsonNode element : node) {
				String value = stringValue(element);
				if (!value.isBlank()) {
					parts.add(value);
				}
			}
			return String.join("; ", parts);
		}
		return node.asText().strip();
	}

	private static List<String> stringList(JsonNode node) {
		List<String> values = new ArrayList<>();
		if (node == null || node.isNull()) {
			return values;
		}
		if (node.isArray()) {
			for (JsonNode element : node) {
				if (element != null && element.isTextual() && !element.asText().isBlank()) {
					values.add(element.asText().strip());
				}
			}
		} else if (node.isTextual() && !node.asText().isBlank()) {
			values.add(node.asText().strip());
		}
		return values;
	}
}
