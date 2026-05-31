package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.retrieval.model.TemporalFact;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TemporalExtractorTests {

  private static final Instant NOW = Instant.parse("2026-05-23T00:00:00Z");
  private final TemporalExtractor extractor = new TemporalExtractor();

  private List<TemporalFact> extract(String query) {
    return extractor.extract(query, NOW, List.of());
  }

  private static Memory event(String content, String payload) {
    return new Memory("e1", "s1", MemoryType.EVENT, content, null, null,
      false, payload, null, Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void noTemporalContentYieldsEmpty() {
    assertThat(extract("what package manager does the user prefer?")).isEmpty();
  }

  @Test
  void nullAndGarbageQueryNeverThrowAndYieldEmpty() {
    assertThatCode(() -> extractor.extract(null, NOW, null)).doesNotThrowAnyException();
    assertThat(extractor.extract(null, NOW, null)).isEmpty();
    assertThat(extract("9999-99-99 zzz")).isEmpty();
  }

  @Test
  void nullRequestTimeYieldsEmpty() {
    assertThat(extractor.extract("today", null, List.of())).isEmpty();
  }

  @Test
  void pastIsoDateProducesDaysAgo() {
    List<TemporalFact> facts = extract("what happened on 2026-01-01?");
    assertThat(facts).extracting(TemporalFact::render)
      .containsExactly("days from 2026-01-01 to today (2026-05-23): 142 days ago");
  }

  @Test
  void futureIsoDateProducesInDays() {
    List<TemporalFact> facts = extract("what is planned for 2026-05-31?");
    assertThat(facts).extracting(TemporalFact::render)
      .containsExactly("days from 2026-05-31 to today (2026-05-23): in 8 days");
  }

  @Test
  void todayIsoDateProducesToday() {
    List<TemporalFact> facts = extract("anything for 2026-05-23?");
    assertThat(facts).extracting(TemporalFact::render)
      .containsExactly("days from 2026-05-23 to today (2026-05-23): today");
  }

  @Test
  void durationBetweenTwoDates() {
    List<TemporalFact> facts = extract("how long between 2026-01-01 and 2026-01-10?");
    assertThat(facts).extracting(TemporalFact::render)
      .contains("days between 2026-01-01 and 2026-01-10: 9 days");
    // span phrase present -> no per-date "days from" facts emitted
    assertThat(facts).hasSize(1);
  }

  @Test
  void durationFromAToB() {
    List<TemporalFact> facts = extract("from 2026-03-01 to 2026-03-02");
    assertThat(facts).extracting(TemporalFact::render)
      .containsExactly("days between 2026-03-01 and 2026-03-02: 1 day");
  }

  @Test
  void todayResolves() {
    assertThat(extract("what did I do today?")).extracting(TemporalFact::render)
      .containsExactly("today resolves to: 2026-05-23");
  }

  @Test
  void yesterdayResolves() {
    assertThat(extract("what about yesterday?")).extracting(TemporalFact::render)
      .containsExactly("yesterday resolves to: 2026-05-22");
  }

  @Test
  void tomorrowResolves() {
    assertThat(extract("anything tomorrow?")).extracting(TemporalFact::render)
      .containsExactly("tomorrow resolves to: 2026-05-24");
  }

  @Test
  void nDaysAgoResolves() {
    assertThat(extract("what happened 3 days ago?")).extracting(TemporalFact::render)
      .containsExactly("3 days ago resolves to: 2026-05-20");
  }

  @Test
  void oneDayAgoIsSingularInLabel() {
    assertThat(extract("1 day ago")).extracting(TemporalFact::render)
      .containsExactly("1 day ago resolves to: 2026-05-22");
  }

  @Test
  void nWeeksAgoResolves() {
    assertThat(extract("2 weeks ago")).extracting(TemporalFact::render)
      .containsExactly("2 weeks ago resolves to: 2026-05-09");
  }

  @Test
  void nMonthsAgoResolves() {
    assertThat(extract("3 months ago")).extracting(TemporalFact::render)
      .containsExactly("3 months ago resolves to: 2026-02-23");
  }

  @Test
  void inNDaysResolves() {
    assertThat(extract("remind me in 5 days")).extracting(TemporalFact::render)
      .containsExactly("in 5 days resolves to: 2026-05-28");
  }

  @Test
  void inNWeeksResolves() {
    assertThat(extract("due in 2 weeks")).extracting(TemporalFact::render)
      .containsExactly("in 2 weeks resolves to: 2026-06-06");
  }

  @Test
  void lastWeekResolves() {
    assertThat(extract("what did we ship last week?")).extracting(TemporalFact::render)
      .containsExactly("last week resolves to: 2026-05-16");
  }

  @Test
  void nextWeekResolves() {
    assertThat(extract("what is due next week?")).extracting(TemporalFact::render)
      .containsExactly("next week resolves to: 2026-05-30");
  }

  @Test
  void daysSincePhraseWithIsoDate() {
    List<TemporalFact> facts = extract("how long ago was 2026-05-01?");
    assertThat(facts).extracting(TemporalFact::render)
      .contains("days from 2026-05-01 to today (2026-05-23): 22 days ago");
  }

  @Test
  void eventOccurredAtProducesDaysSince() {
    Memory ev = event("deployed v2", "{\"occurred_at\":\"2026-05-01T10:00:00Z\"}");
    List<TemporalFact> facts = extractor.extract("how long ago did we deploy?", NOW, List.of(ev));
    assertThat(facts).extracting(TemporalFact::render)
      .contains("days since event (2026-05-01): deployed v2: 22 days ago");
  }

  @Test
  void eventOccurredAtBareDate() {
    Memory ev = event("launch", "{\"occurred_at\": \"2026-05-22\"}");
    List<TemporalFact> facts = extractor.extract("when was the launch?", NOW, List.of(ev));
    assertThat(facts).extracting(TemporalFact::render)
      .contains("days since event (2026-05-22): launch: 1 day ago");
  }

  @Test
  void eventIgnoredWhenQueryNotTemporalSince() {
    Memory ev = event("deployed v2", "{\"occurred_at\":\"2026-05-01T10:00:00Z\"}");
    // query has no "how long ago"/"when"/"since" phrasing -> event not emitted
    List<TemporalFact> facts = extractor.extract("tell me about the deploy", NOW, List.of(ev));
    assertThat(facts).isEmpty();
  }

  @Test
  void nonEventMemoryIgnoredEvenWithOccurredAt() {
    Memory fact = new Memory("f1", "s1", MemoryType.FACT, "x", null, null,
      false, "{\"occurred_at\":\"2026-05-01\"}", null, NOW);
    List<TemporalFact> facts = extractor.extract("when did x happen?", NOW, List.of(fact));
    assertThat(facts).isEmpty();
  }

  @Test
  void eventWithMalformedPayloadSkippedNoThrow() {
    Memory ev = event("bad", "{not json at all");
    assertThatCode(() -> extractor.extract("how long ago?", NOW, List.of(ev)))
      .doesNotThrowAnyException();
    assertThat(extractor.extract("how long ago?", NOW, List.of(ev))).isEmpty();
  }
}
