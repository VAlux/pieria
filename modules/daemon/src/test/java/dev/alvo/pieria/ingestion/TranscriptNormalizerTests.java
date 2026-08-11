package dev.alvo.pieria.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.alvo.pieria.domain.memory.Message;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TranscriptNormalizerTests {

  private final TranscriptNormalizer normalizer = new TranscriptNormalizer();
  // 2026-05-22T12:00:00Z
  private final Instant requestTime = Instant.parse("2026-05-22T12:00:00Z");

  @Test
  void dropsMessagesWithBlankRoleContentOrSession() {
    List<Message> messages = Arrays.asList(
      Message.of("s1", "user", "valid"),
      Message.of("s1", " ", "blank role"),
      Message.of("s1", "user", "  "),
      new Message(null, null, "user", "no session", null),
      Message.of("s1", "assistant", "also valid"));

    List<Message> result = normalizer.normalize(messages, requestTime);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).content()).isEqualTo("valid");
    assertThat(result.get(1).content()).isEqualTo("also valid");
  }

  @Test
  void preservesSourceOrder() {
    List<Message> messages = List.of(
      Message.of("s1", "user", "first"),
      Message.of("s1", "assistant", "second"),
      Message.of("s1", "user", "third"));

    List<Message> result = normalizer.normalize(messages, requestTime);

    assertThat(result).extracting(Message::content).containsExactly("first", "second", "third");
  }

  @Test
  void resolvesObviousRelativeDates() {
    List<Message> messages = List.of(
      Message.of("s1", "user", "We met yesterday and ship tomorrow, today is the demo."));

    List<Message> result = normalizer.normalize(messages, requestTime);

    assertThat(result.get(0).content())
      .isEqualTo("We met 2026-05-21 and ship 2026-05-23, 2026-05-22 is the demo.");
  }

  @Test
  void relativeDateMatchingIsCaseInsensitiveAndWordBounded() {
    List<Message> messages = List.of(
      Message.of("s1", "user", "Yesterday's yesterdayish note"));

    List<Message> result = normalizer.normalize(messages, requestTime);

    // "Yesterday" replaced; "yesterdayish" (no word boundary) untouched.
    assertThat(result.get(0).content()).isEqualTo("2026-05-21's yesterdayish note");
  }

  @Test
  void relativeDatesResolveAgainstTheMessagesOwnTimestampWhenItHasOne() {
    // A back-filled transcript: the turn was spoken in 2023 but is being ingested in 2026.
    Instant spokenAt = Instant.parse("2023-05-08T13:56:00Z");
    List<Message> messages = List.of(
      new Message(null, "s1", "user", "I went to the support group yesterday.", spokenAt));

    List<Message> result = normalizer.normalize(messages, requestTime);

    assertThat(result.get(0).content()).isEqualTo("I went to the support group 2023-05-07.");
    assertThat(result.get(0).createdAt()).isEqualTo(spokenAt);
  }

  @Test
  void eachMessageResolvesAgainstItsOwnSessionSoAMultiMonthTranscriptStaysCorrect() {
    List<Message> messages = List.of(
      new Message(null, "s1", "user", "met yesterday", Instant.parse("2023-05-08T13:56:00Z")),
      new Message(null, "s1", "user", "met yesterday", Instant.parse("2023-06-09T10:15:00Z")),
      Message.of("s1", "user", "met yesterday"));

    List<Message> result = normalizer.normalize(messages, requestTime);

    assertThat(result).extracting(Message::content).containsExactly(
      "met 2023-05-07",
      "met 2023-06-08",
      "met 2026-05-21"); // no timestamp of its own → the request time
  }

  @Test
  void rendersRoleLabeledTranscriptWithLineIndices() {
    List<Message> messages = List.of(
      Message.of("s1", "user", "hello"),
      Message.of("s1", "assistant", "hi there"));

    String rendered = normalizer.render(messages);

    assertThat(rendered).isEqualTo("[0] user: hello\n[1] assistant: hi there");
  }

  @Test
  void renderHonorsExplicitStartIndexForProvenance() {
    List<Message> messages = List.of(
      Message.of("s1", "user", "a"),
      Message.of("s1", "assistant", "b"));

    String rendered = normalizer.render(messages, 5);

    assertThat(rendered).isEqualTo("[5] user: a\n[6] assistant: b");
  }

  @Test
  void nullInputYieldsEmptyList() {
    assertThat(normalizer.normalize(null, requestTime)).isEmpty();
  }
}
