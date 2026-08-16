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
  void replacesRelativePeriodsAtTheSpeakersOwnGranularity() {
    // Replacement, not annotation: an appended "(June 2026)" is detachable, and extraction was
    // observed re-attaching it to a neighbouring clause, stranding "next month" with no anchor.
    List<Message> messages = List.of(
      Message.of("s1", "user", "We're going camping next month and I moved last year."));

    List<Message> result = normalizer.normalize(messages, requestTime);

    assertThat(result.get(0).content())
      .isEqualTo("We're going camping June 2026 and I moved 2025.");
  }

  @Test
  void resolvesEveryRelativePeriodUnitAgainstTheSpeakingDate() {
    // Friday 2023-05-26: ISO week starts Monday the 22nd, its weekend Saturday is the 27th.
    Instant spokenAt = Instant.parse("2023-05-26T09:00:00Z");
    List<Message> messages = List.of(
      new Message(null, "s1", "user",
        "last week, this week, next week, this weekend, last weekend, this month, this year",
        spokenAt));

    List<Message> result = normalizer.normalize(messages, requestTime);

    assertThat(result.get(0).content()).isEqualTo(
      "the week of 2023-05-15, the week of 2023-05-22, the week of 2023-05-29, "
        + "the weekend of 2023-05-27, the weekend of 2023-05-20, May 2023, 2023");
  }

  @Test
  void treatsPastAndComingAsLastAndNext() {
    List<Message> messages = List.of(
      Message.of("s1", "user", "the past month was rough but the coming year looks good"));

    List<Message> result = normalizer.normalize(messages, requestTime);

    assertThat(result.get(0).content())
      .isEqualTo("April 2026 was rough but 2027 looks good");
  }

  @Test
  void aLeadingArticleIsConsumedForMonthsButReinstatedForWeeks() {
    List<Message> messages = List.of(
      Message.of("s1", "user", "I rested the last week and travelled the next month"));

    List<Message> result = normalizer.normalize(messages, requestTime);

    assertThat(result.get(0).content())
      .isEqualTo("I rested the week of 2026-05-11 and travelled June 2026");
  }

  @Test
  void leavesGenuinelyFuzzyPeriodsForTheModel() {
    // Seasons are hemisphere-dependent and have no calendar definition, so they stay as written.
    List<Message> messages = List.of(
      Message.of("s1", "user", "we hiked last summer and might go again next spring, a while back"));

    List<Message> result = normalizer.normalize(messages, requestTime);

    assertThat(result.get(0).content())
      .isEqualTo("we hiked last summer and might go again next spring, a while back");
  }

  @Test
  void relativePeriodsAnchorOnEachMessagesOwnTimestamp() {
    // The whole point of the fix: a back-filled 2023 transcript must not resolve against the ingest
    // wall clock, or every period lands three years out.
    List<Message> messages = List.of(
      new Message(null, "s1", "user", "camping next month", Instant.parse("2023-05-25T13:14:00Z")),
      new Message(null, "s1", "user", "camping next month", Instant.parse("2023-12-09T10:15:00Z")));

    List<Message> result = normalizer.normalize(messages, requestTime);

    assertThat(result).extracting(Message::content).containsExactly(
      "camping June 2023",
      "camping January 2024"); // rolls the year over
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
