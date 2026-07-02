package dev.alvo.pieria.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Parses the real <a href="https://github.com/snap-research/locomo">LoCoMo</a> dataset
 * ({@code locomo10.json}) into {@link EvaluationFixture}s the harness can drive.
 *
 * <h2>Encoded schema assumptions (defensive — missing/extra fields are tolerated)</h2>
 * The on-disk file is a JSON <em>array</em> of samples. Each sample object is assumed to contain:
 * <ul>
 *   <li>{@code sample_id} — string id (defaults to {@code locomo-<index>} when absent), used as the
 *       fixture name and ingest session id.</li>
 *   <li>{@code conversation} — an object whose keys are either speaker labels
 *       ({@code speaker_a}, {@code speaker_b}) or per-session entries. Session turns live under
 *       numbered keys {@code session_1}, {@code session_2}, … each holding an array of turn objects;
 *       a sibling {@code session_<n>_date_time} string carries the session timestamp. Turns are
 *       parsed in ascending session order, then array order. Each turn object is assumed to expose a
 *       {@code speaker} (or {@code role}) and a {@code text} (or {@code clean_text} / {@code value}
 *       / {@code content}) field; turns with blank text are skipped. Image-only turns expose a
 *       {@code blip_caption} we fold into the text when {@code text} is empty.</li>
 *   <li>{@code qa} — an array of question/answer objects. Each is assumed to expose {@code question}
 *       and {@code answer} (coerced to a string; numeric/boolean answers are stringified). An
 *       optional {@code evidence} field (string or array of strings, dialog ids such as
 *       {@code "D1:2"}) is <em>resolved to the referenced turn text</em> via each turn's
 *       {@code dia_id} and recorded as expected recall evidence — the raw ids never match a
 *       retrieved memory, so resolving them is what makes retrieval hit-rate/MRR meaningful for
 *       LoCoMo. Ids that cannot be resolved are kept verbatim (they simply never match).
 *       {@code category 5} (adversarial, answer often "Not mentioned") is kept as-is — the harness
 *       still scores it.</li>
 * </ul>
 *
 * <p>The transcript is mapped to ingest messages by alternating user/assistant roles is NOT done —
 * LoCoMo is two human speakers, so the first speaker maps to {@code user} and the second to
 * {@code assistant} to fit the daemon's role model, with the speaker name preserved inline in the
 * message text so the model can still attribute turns. Expected memories are intentionally left
 * empty: LoCoMo provides no gold extraction set, only QA, so extraction precision/recall are not
 * meaningful for this benchmark and the harness reports them as vacuous.
 */
public final class LoCoMoBenchmarkAdapter {

	private static final String PROFILE = "locomo-eval";

	private final ObjectMapper objectMapper;

	public LoCoMoBenchmarkAdapter(ObjectMapper objectMapper) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
	}

	public LoCoMoBenchmarkAdapter() {
		this(new ObjectMapper());
	}

	/** Loads and parses a local {@code locomo10.json} file. */
	public List<EvaluationFixture> load(Path datasetFile) throws IOException {
		try (InputStream in = Files.newInputStream(datasetFile)) {
			return parse(in);
		}
	}

	public List<EvaluationFixture> parse(InputStream in) throws IOException {
		return parse(objectMapper.readTree(in));
	}

	/** Parses an already-read JSON tree (array of samples). */
	public List<EvaluationFixture> parse(JsonNode root) {
		List<EvaluationFixture> fixtures = new ArrayList<>();
		if (root == null) {
			return fixtures;
		}
		Iterable<JsonNode> samples = root.isArray() ? root : List.of(root);
		int index = 0;
		for (JsonNode sample : samples) {
			index++;
			EvaluationFixture fixture = parseSample(sample, index);
			if (fixture != null) {
				fixtures.add(fixture);
			}
		}
		return fixtures;
	}

	private EvaluationFixture parseSample(JsonNode sample, int index) {
		if (sample == null || !sample.isObject()) {
			return null;
		}
		String sampleId = text(sample, "sample_id", "id");
		String name = sampleId.isBlank() ? "locomo-" + index : sampleId;
		String sessionId = "locomo-" + name;

		// dia_id -> turn text, populated during conversation parsing so QA evidence ids resolve to text.
		Map<String, String> evidenceText = new LinkedHashMap<>();
		List<EvaluationFixture.TranscriptMessage> transcript = parseConversation(sample.get("conversation"), evidenceText);
		List<EvaluationFixture.RecallExpectation> recalls = parseQa(sample.get("qa"), evidenceText);

		if (transcript.isEmpty() || recalls.isEmpty()) {
			return null;
		}
		return new EvaluationFixture(name, PROFILE, sessionId, transcript, List.of(), recalls);
	}

	private List<EvaluationFixture.TranscriptMessage> parseConversation(JsonNode conversation,
	                                                                    Map<String, String> evidenceText) {
		List<EvaluationFixture.TranscriptMessage> messages = new ArrayList<>();
		if (conversation == null || !conversation.isObject()) {
			return messages;
		}

		// Speaker labels (optional) — used only to keep first/second speaker stable across sessions.
		String speakerA = text(conversation, "speaker_a");

		// Collect numbered sessions in ascending order so the dialogue stays chronological.
		Map<Integer, JsonNode> sessions = new TreeMap<>();
		Iterator<Map.Entry<String, JsonNode>> it = conversation.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> entry = it.next();
			Integer n = sessionNumber(entry.getKey());
			if (n != null && entry.getValue() != null && entry.getValue().isArray()) {
				sessions.put(n, entry.getValue());
			}
		}

		for (JsonNode session : sessions.values()) {
			for (JsonNode turn : session) {
				String body = turnBody(turn);
				EvaluationFixture.TranscriptMessage message = parseTurn(turn, speakerA, body);
				if (message != null) {
					messages.add(message);
					String diaId = text(turn, "dia_id", "id");
					if (!diaId.isBlank()) {
						// Evidence is compared against retrieved memory text, so record the turn body
						// (without the speaker prefix) as the resolvable text for this dialog id.
						evidenceText.put(diaId, body);
					}
				}
			}
		}
		return messages;
	}

	private static String turnBody(JsonNode turn) {
		if (turn == null || !turn.isObject()) {
			return "";
		}
		String body = text(turn, "text", "clean_text", "value", "content");
		if (body.isBlank()) {
			body = text(turn, "blip_caption", "caption");
		}
		return body;
	}

	private EvaluationFixture.TranscriptMessage parseTurn(JsonNode turn, String speakerA, String body) {
		if (turn == null || !turn.isObject() || body.isBlank()) {
			return null;
		}
		String speaker = text(turn, "speaker", "role", "from");
		// Two-human dialogue → first speaker = user, everyone else = assistant; keep the name inline.
		String role = speaker.isBlank() || speaker.equalsIgnoreCase(speakerA) ? "user" : "assistant";
		String content = speaker.isBlank() ? body : speaker + ": " + body;
		return new EvaluationFixture.TranscriptMessage(role, content);
	}

	private List<EvaluationFixture.RecallExpectation> parseQa(JsonNode qa, Map<String, String> evidenceText) {
		List<EvaluationFixture.RecallExpectation> recalls = new ArrayList<>();
		if (qa == null || !qa.isArray()) {
			return recalls;
		}
		for (JsonNode item : qa) {
			if (item == null || !item.isObject()) {
				continue;
			}
			String question = text(item, "question", "query");
			String answer = stringValue(item.get("answer"));
			if (answer.isBlank()) {
				answer = stringValue(item.get("adversarial_answer"));
			}
			if (question.isBlank() || answer.isBlank()) {
				continue;
			}
			List<String> evidence = resolveEvidence(stringList(item.get("evidence")), evidenceText);
			recalls.add(new EvaluationFixture.RecallExpectation(question, evidence, answer));
		}
		return recalls;
	}

	/**
	 * Maps dialog ids (e.g. {@code "D1:2"}) to the referenced turn text. Ids with no matching
	 * {@code dia_id} are kept verbatim so the denominator stays honest — they simply never match a
	 * retrieved memory.
	 */
	private static List<String> resolveEvidence(List<String> evidenceIds, Map<String, String> evidenceText) {
		List<String> resolved = new ArrayList<>();
		for (String id : evidenceIds) {
			String turnText = evidenceText.get(id);
			String value = turnText != null && !turnText.isBlank() ? turnText : id;
			if (!resolved.contains(value)) {
				resolved.add(value);
			}
		}
		return resolved;
	}

	private static Integer sessionNumber(String key) {
		if (key == null || !key.startsWith("session_") || key.contains("date") || key.contains("summary")) {
			return null;
		}
		String suffix = key.substring("session_".length());
		try {
			return Integer.parseInt(suffix);
		} catch (NumberFormatException e) {
			return null;
		}
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
				String value = stringValue(element);
				if (!value.isBlank() && !values.contains(value)) {
					values.add(value);
				}
			}
		} else {
			String value = stringValue(node);
			if (!value.isBlank()) {
				values.add(value);
			}
		}
		values.sort(Comparator.naturalOrder());
		return values;
	}
}
