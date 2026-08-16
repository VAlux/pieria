package dev.alvo.pieria.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.alvo.pieria.evaluation.EvaluationFixture.RecallExpectation;
import dev.alvo.pieria.evaluation.EvaluationFixture.TranscriptMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Parses the real <a href="https://github.com/snap-research/locomo">LoCoMo</a> dataset
 * ({@code locomo10.json}) into {@link EvaluationFixture}s the harness can drive, applying the
 * {@link BenchmarkConfig} subset filters as it goes.
 *
 * <h2>Encoded schema assumptions (defensive — missing/extra fields are tolerated)</h2>
 * The on-disk file is a JSON <em>array</em> of samples. Each sample object is assumed to contain:
 * <ul>
 *   <li>{@code sample_id} — string id (defaults to {@code locomo-<index>} when absent), used as the
 *       fixture name, the ingest session id, and the {@code --conversations=<ids>} selector.</li>
 *   <li>{@code conversation} — an object whose keys are either speaker labels
 *       ({@code speaker_a}, {@code speaker_b}) or per-session entries. Session turns live under
 *       numbered keys {@code session_1}, {@code session_2}, … each holding an array of turn objects;
 *       a sibling {@code session_<n>_date_time} string carries the session timestamp. Turns are
 *       parsed in ascending session order, then array order. Each turn object is assumed to expose a
 *       {@code speaker} (or {@code role}) and a {@code text} (or {@code clean_text} / {@code value}
 *       / {@code content}) field; turns with blank text are skipped. Image-only turns expose a
 *       {@code blip_caption} we fold into the text when {@code text} is empty.</li>
 *   <li>{@code qa} — an array of question/answer objects exposing {@code question}, {@code answer},
 *       and {@code category}. An entry carrying only {@code adversarial_answer} (category 5) is an
 *       <em>adversarial</em> question: that text is the trap the question baits, not a gold answer,
 *       so the expectation is recorded as abstention-expected (see
 *       {@link EvaluationFixture.RecallExpectation}). The
 *       {@code evidence} field (string or array of dialog ids such as {@code "D1:2"}) is
 *       <em>resolved to the referenced turn text</em> via each turn's {@code dia_id} and recorded as
 *       expected recall evidence — the raw ids never match a retrieved memory, so resolving them is
 *       what makes retrieval hit-rate/MRR meaningful. Ids that cannot be resolved are kept verbatim
 *       (they simply never match).</li>
 * </ul>
 *
 * <h2>Session timestamps</h2>
 * Every turn is ingested prefixed with its session's date — {@code "[1:56 pm on 8 May, 2023]
 * Caroline: …"} — so each chunk the daemon extracts from carries the date. Without this, LoCoMo's
 * category-2 (temporal) questions, whose gold answers <em>are</em> dates, are unanswerable by
 * construction. The resolved evidence text deliberately keeps the <em>undated</em> turn body, so the
 * harness's token-containment scoring is unaffected by the prefix.
 *
 * <h2>Roles</h2>
 * LoCoMo is two human speakers, so the first speaker maps to {@code user} and the second to
 * {@code assistant} to fit the daemon's role model, with the speaker name preserved inline so the
 * model can still attribute turns.
 */
public final class LoCoMoBenchmarkAdapter {

  private static final String PROFILE = "locomo-eval";

  /** {@code "1:56 pm on 8 May, 2023"} — case-insensitive so the dataset's lowercase am/pm parses. */
  private static final DateTimeFormatter SESSION_DATE_TIME = new DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .appendPattern("h:mm a 'on' d MMMM, yyyy")
    .toFormatter(Locale.ENGLISH);

  private final ObjectMapper objectMapper;

  public LoCoMoBenchmarkAdapter(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public LoCoMoBenchmarkAdapter() {
    this(new ObjectMapper());
  }

  /** Loads and parses a local {@code locomo10.json} file, applying the config's subset filters. */
  public List<EvaluationFixture> load(Path datasetFile, BenchmarkConfig config) throws IOException {
    try (InputStream in = Files.newInputStream(datasetFile)) {
      return parse(in, config);
    }
  }

  public List<EvaluationFixture> parse(InputStream in, BenchmarkConfig config) throws IOException {
    return parse(objectMapper.readTree(in), config);
  }

  /** Parses an already-read JSON tree (array of samples). */
  public List<EvaluationFixture> parse(JsonNode root, BenchmarkConfig config) {
    List<EvaluationFixture> fixtures = new ArrayList<>();
    if (root == null) {
      return fixtures;
    }
    Iterable<JsonNode> samples = root.isArray() ? root : List.of(root);
    List<String> matchedIds = new ArrayList<>();
    int index = 0;
    for (JsonNode sample : samples) {
      index++;
      String name = sampleName(sample, index);
      if (!config.conversationIds().isEmpty()) {
        if (!config.conversationIds().contains(name)) {
          continue;
        }
        matchedIds.add(name);
      } else if (config.conversations() > 0 && fixtures.size() >= config.conversations()) {
        break;
      }
      EvaluationFixture fixture = parseSample(sample, name, config);
      if (fixture != null) {
        fixtures.add(fixture);
      }
    }

    // A typo'd id would otherwise silently run a different (or empty) slice for hours.
    List<String> missing = new ArrayList<>(config.conversationIds());
    missing.removeAll(matchedIds);
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("no conversation in the dataset matches: " + missing);
    }
    return fixtures;
  }

  private static String sampleName(JsonNode sample, int index) {
    String sampleId = sample == null ? "" : text(sample, "sample_id", "id");
    return sampleId.isBlank() ? "locomo-" + index : sampleId;
  }

  private EvaluationFixture parseSample(JsonNode sample, String name, BenchmarkConfig config) {
    if (sample == null || !sample.isObject()) {
      return null;
    }

    // dia_id -> undated turn text, populated while parsing so QA evidence ids resolve to text.
    Map<String, String> evidenceText = new LinkedHashMap<>();
    List<TranscriptMessage> transcript =
      parseConversation(sample.get("conversation"), config.sessions(), evidenceText);
    List<RecallExpectation> recalls = parseQa(sample.get("qa"), evidenceText, config);

    if (transcript.isEmpty() || recalls.isEmpty()) {
      return null;
    }
    return new EvaluationFixture(name, PROFILE, "locomo-" + name, transcript, recalls);
  }

  private List<TranscriptMessage> parseConversation(JsonNode conversation,
                                                    int sessionLimit,
                                                    Map<String, String> evidenceText) {
    List<TranscriptMessage> messages = new ArrayList<>();
    if (conversation == null || !conversation.isObject()) {
      return messages;
    }

    // Speaker labels (optional) — used only to keep first/second speaker stable across sessions.
    String speakerA = text(conversation, "speaker_a");

    // Collect numbered sessions in ascending order so the dialogue stays chronological.
    Map<Integer, JsonNode> sessions = new TreeMap<>();
    for (Map.Entry<String, JsonNode> entry : conversation.properties()) {
      Integer n = sessionNumber(entry.getKey());
      if (n != null && entry.getValue() != null && entry.getValue().isArray()
        && (sessionLimit <= 0 || n <= sessionLimit)) {
        sessions.put(n, entry.getValue());
      }
    }

    for (Map.Entry<Integer, JsonNode> session : sessions.entrySet()) {
      String dateTime = text(conversation, "session_" + session.getKey() + "_date_time");
      Instant spokenAt = parseSessionDateTime(dateTime);
      for (JsonNode turn : session.getValue()) {
        String body = turnBody(turn);
        TranscriptMessage message = parseTurn(turn, speakerA, body, dateTime, spokenAt);
        if (message != null) {
          messages.add(message);
          String diaId = text(turn, "dia_id", "id");
          if (!diaId.isBlank()) {
            // Evidence is compared against retrieved memory text, so record the bare turn body
            // (no speaker prefix, no date prefix) as the resolvable text for this dialog id.
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

  private TranscriptMessage parseTurn(JsonNode turn, String speakerA, String body, String dateTime,
                                      Instant spokenAt) {
    if (turn == null || !turn.isObject() || body.isBlank()) {
      return null;
    }
    String speaker = text(turn, "speaker", "role", "from");
    // Two-human dialogue → first speaker = user, everyone else = assistant; keep the name inline.
    String role = speaker.isBlank() || speaker.equalsIgnoreCase(speakerA) ? "user" : "assistant";
    StringBuilder content = new StringBuilder();
    if (!dateTime.isBlank()) {
      content.append('[').append(dateTime).append("] ");
    }
    if (!speaker.isBlank()) {
      content.append(speaker).append(": ");
    }
    content.append(body);
    return new TranscriptMessage(role, content.toString(), spokenAt);
  }

  /**
   * Parses LoCoMo's session stamp — {@code "1:56 pm on 8 May, 2023"}, the single shape used by all
   * 288 sessions in {@code locomo10.json} — into an instant, treated as UTC.
   *
   * <p>The stamp is sent to the daemon as each turn's message timestamp, which is what makes the
   * transcript's relative dates ("yesterday") resolve against 2023 rather than the ingest wall clock.
   * The prefix in the turn text handles the rest; the two work together. Unparseable ⇒ {@code null},
   * which just falls back to the daemon's clock.
   */
  private static Instant parseSessionDateTime(String dateTime) {
    if (dateTime == null || dateTime.isBlank()) {
      return null;
    }
    try {
      return LocalDateTime.parse(dateTime.strip(), SESSION_DATE_TIME).toInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private List<RecallExpectation> parseQa(JsonNode qa,
                                          Map<String, String> evidenceText,
                                          BenchmarkConfig config) {
    List<RecallExpectation> recalls = new ArrayList<>();
    if (qa == null || !qa.isArray()) {
      return recalls;
    }

    for (JsonNode item : qa) {
      if (item == null || !item.isObject()) {
        continue;
      }
      int category = item.path("category").asInt(0);
      if (!config.acceptsCategory(category)) {
        continue;
      }
      String question = text(item, "question", "query");
      // An `adversarial_answer` with no `answer` is the trap, not the gold: the question misattributes
      // a real fact to the other speaker, and the correct behaviour is to decline. Scoring it as the
      // expected answer inverts the metric for roughly a quarter of the corpus.
      String answer = stringValue(item.get("answer"));
      boolean expectAbstention = answer.isBlank();
      if (expectAbstention) {
        answer = stringValue(item.get("adversarial_answer"));
      }
      if (question.isBlank() || answer.isBlank()) {
        continue;
      }
      List<String> evidenceIds = stringList(item.get("evidence"));
      if (!withinSessionLimit(evidenceIds, config.sessions())) {
        continue;
      }
      recalls.add(new RecallExpectation(
        question, resolveEvidence(evidenceIds, evidenceText), answer, category, expectAbstention));
    }
    return sample(recalls, config.questions());
  }

  /**
   * With a {@code --sessions} cap, a question whose evidence lives in a dropped session is
   * unanswerable — dropping it keeps the subset's score honest. Dialog ids are {@code D<session>:<turn>};
   * a question with no evidence at all cannot be placed, so it is dropped too whenever a cap is active.
   */
  private static boolean withinSessionLimit(List<String> evidenceIds, int sessionLimit) {
    if (sessionLimit <= 0) {
      return true;
    }
    if (evidenceIds.isEmpty()) {
      return false;
    }
    for (String id : evidenceIds) {
      Integer session = evidenceSession(id);
      if (session == null || session > sessionLimit) {
        return false;
      }
    }
    return true;
  }

  private static Integer evidenceSession(String evidenceId) {
    if (evidenceId == null || !evidenceId.startsWith("D")) {
      return null;
    }
    int colon = evidenceId.indexOf(':');
    String digits = colon < 0 ? evidenceId.substring(1) : evidenceId.substring(1, colon);
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Takes {@code limit} questions spread evenly across the list rather than the first {@code limit}:
   * LoCoMo's {@code qa} array is loosely ordered by category, so a head slice would silently bias a
   * subset run towards one reasoning type.
   */
  private static List<RecallExpectation> sample(List<RecallExpectation> recalls, int limit) {
    if (limit <= 0 || recalls.size() <= limit) {
      return recalls;
    }
    int stride = Math.max(1, Math.ceilDiv(recalls.size(), limit));
    List<RecallExpectation> sampled = new ArrayList<>(limit);
    for (int i = 0; i < recalls.size() && sampled.size() < limit; i += stride) {
      sampled.add(recalls.get(i));
    }
    // A stride that overshoots can leave the tail short; top up from the end, skipping duplicates.
    for (int i = recalls.size() - 1; i >= 0 && sampled.size() < limit; i--) {
      RecallExpectation candidate = recalls.get(i);
      if (!sampled.contains(candidate)) {
        sampled.add(candidate);
      }
    }
    return List.copyOf(sampled);
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
