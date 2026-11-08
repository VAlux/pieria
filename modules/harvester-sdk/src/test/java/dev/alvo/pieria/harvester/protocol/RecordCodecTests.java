package dev.alvo.pieria.harvester.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordCodecTests {

  private static final Instant T = Instant.parse("2026-01-02T03:04:05Z");
  private static final HarvestRecord.Document DOC = new HarvestRecord.Document("a", "v1", "Title", "https://x/a", "Ann", T,
    DocumentFormat.MARKDOWN, "# Hi\nline two", Map.of("k", "v"));

  private static HarvestRecord.Turn turn(String id, String text) {
    return new HarvestRecord.Turn(id, "Ann", T, text);
  }

  private static HarvestRecord.Document docWithText(String text) {
    return new HarvestRecord.Document("a", null, "T", "https://x/a", null, T, DocumentFormat.TEXT, text, null);
  }

  @Test
  void everyRecordRoundTrips() {
    for (HarvestRecord record : List.of(DOC,
      new HarvestRecord.ConversationThread("t", "T", "https://x/t", List.of(turn("1", "hello")), Map.of()),
      new HarvestRecord.Tombstone("a"), new HarvestRecord.Seen(List.of("a", "b")), new HarvestRecord.Sweep(),
      new HarvestRecord.Progress(3, 10L), new HarvestRecord.SecretUpdate("token", "s3"),
      new HarvestRecord.Failure(ErrorClass.RATE_LIMITED, "slow down", 30L))) {
      assertThat(RecordCodec.decode(RecordCodec.encode(record))).isEqualTo(record);
    }
  }

  @Test
  void checkpointCarriesAnyJsonCursor() {
    JsonNode cursor = new ObjectMapper().readTree("{\"since\":\"x\",\"pages\":[1,2]}");

    assertThat(RecordCodec.decode(RecordCodec.encode(new HarvestRecord.Checkpoint(cursor))))
      .isEqualTo(new HarvestRecord.Checkpoint(cursor));
  }

  @Test
  void encodedRecordIsOneLine() {
    assertThat(RecordCodec.encode(DOC)).doesNotContain("\n");
  }

  @Test
  void wireNamesMatchTheSpec() {
    assertThat(RecordCodec.encode(new HarvestRecord.Failure(ErrorClass.AUTH, "no", 5L)))
      .contains("\"type\":\"error\"", "\"class\":\"auth\"", "\"retryAfter\":5");
    assertThat(RecordCodec.encode(DOC))
      .contains("\"type\":\"document\"", "\"updatedAt\":\"2026-01-02T03:04:05Z\"", "\"format\":\"markdown\"");
    assertThat(RecordCodec.encode(new HarvestRecord.SecretUpdate("a", "b"))).contains("\"type\":\"secret_update\"");
  }

  @Test
  void acceptsTimestampWithOffset() {
    HarvestRecord decoded = RecordCodec.decode("{\"type\":\"document\",\"id\":\"a\",\"title\":\"T\",\"url\":\"https://x\","
      + "\"updatedAt\":\"2026-01-02T05:04:05+02:00\",\"format\":\"text\",\"text\":\"x\"}");

    assertThat(((HarvestRecord.Document) decoded).updatedAt()).isEqualTo(T);
  }

  @Test
  void optionalFieldsMayBeAbsent() {
    HarvestRecord.Document decoded = (HarvestRecord.Document) RecordCodec.decode("{\"type\":\"document\",\"id\":\"a\",\"title\":\"\","
      + "\"url\":\"https://x\",\"updatedAt\":\"2026-01-02T03:04:05Z\",\"format\":\"text\",\"text\":\"\"}");

    assertThat(decoded.version()).isNull();
    assertThat(decoded.author()).isNull();
    assertThat(decoded.metadata()).isEmpty();
  }

  @Test
  void unknownFieldsAreIgnored() {
    assertThat(RecordCodec.decode("{\"type\":\"sweep\",\"future\":1}")).isEqualTo(new HarvestRecord.Sweep());
  }

  @ParameterizedTest
  @ValueSource(strings = {"not json", "[1]", "{}", "{\"type\":\"mystery\"}", "{\"type\":\"tombstone\"}",
    "{\"type\":\"tombstone\",\"id\":7}", "{\"type\":\"tombstone\",\"id\":\" \"}", "{\"type\":\"seen\",\"ids\":[]}",
    "{\"type\":\"checkpoint\",\"cursor\":null}", "{\"type\":\"checkpoint\"}", "{\"type\":\"progress\",\"done\":-1}",
    "{\"type\":\"progress\",\"done\":1.5}", "{\"type\":\"error\",\"class\":\"odd\",\"message\":\"x\"}"})
  void malformedRecordsAreViolations(String line) {
    assertThatThrownBy(() -> RecordCodec.decode(line)).isInstanceOf(ProtocolViolation.class);
  }

  @Test
  void rejectsBadTimestamp() {
    assertThatThrownBy(() -> RecordCodec.decode("{\"type\":\"document\",\"id\":\"a\",\"title\":\"T\",\"url\":\"https://x\","
      + "\"updatedAt\":\"yesterday\",\"format\":\"text\",\"text\":\"x\"}")).isInstanceOf(ProtocolViolation.class);
  }

  @Test
  void rejectsOverCapText() {
    assertThatThrownBy(() -> docWithText("x".repeat(ProtocolLimits.MAX_TEXT_CHARS + 1)))
      .isInstanceOf(ProtocolViolation.class);
  }

  @Test
  void acceptsTextAtTheCap() {
    String encoded = RecordCodec.encode(docWithText("\n".repeat(ProtocolLimits.MAX_TEXT_CHARS)));

    assertThat(((HarvestRecord.Document) RecordCodec.decode(encoded)).text()).hasSize(ProtocolLimits.MAX_TEXT_CHARS);
  }

  @Test
  void rejectsThreadOverCapAcrossTurns() {
    String half = "x".repeat(ProtocolLimits.MAX_TEXT_CHARS / 2 + 1);

    assertThatThrownBy(() -> new HarvestRecord.ConversationThread("t", "T", "https://x", List.of(turn("1", half), turn("2", half)), null))
      .isInstanceOf(ProtocolViolation.class);
  }

  @Test
  void rejectsThreadWithoutTurns() {
    assertThatThrownBy(() -> new HarvestRecord.ConversationThread("t", "T", "https://x", List.of(), null))
      .isInstanceOf(ProtocolViolation.class);
  }

  @Test
  void rejectsDuplicateTurnIds() {
    assertThatThrownBy(() -> new HarvestRecord.ConversationThread("t", "T", "https://x", List.of(turn("1", "a"), turn("1", "b")), null))
      .isInstanceOf(ProtocolViolation.class);
  }

  @Test
  void rejectsOverCapCursor() {
    JsonNode big = new ObjectMapper().createObjectNode().put("x", "y".repeat(ProtocolLimits.MAX_CURSOR_CHARS));

    assertThatThrownBy(() -> new HarvestRecord.Checkpoint(big)).isInstanceOf(ProtocolViolation.class);
  }

  @Test
  void failureMessageIsTruncatedNotRejected() {
    assertThat(new HarvestRecord.Failure(ErrorClass.FATAL, "x".repeat(5000), null).message())
      .hasSize(ProtocolLimits.MAX_ERROR_CHARS);
  }

  @Test
  void secretUpdateNeverPrintsItsValue() {
    assertThat(new HarvestRecord.SecretUpdate("token", "hunter2").toString()).doesNotContain("hunter2");
  }
}
