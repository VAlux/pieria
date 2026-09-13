package dev.alvo.pieria.ingestion;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class EffectiveDatesTests {
  @Test void calendarPeriodIsBoundedButUncertainChangeIsNotInvented() {
    var anchor = Instant.parse("2026-02-10T14:00:00Z");
    var march = EffectiveDates.resolve("next month", anchor, true);
    assertEquals(Instant.parse("2026-03-01T00:00:00Z"), march.from());
    assertEquals(Instant.parse("2026-04-01T00:00:00Z"), march.to());
    assertNull(EffectiveDates.resolve("next month", anchor, false));
    assertNull(EffectiveDates.resolve("sometime in May", anchor, true));
    assertNull(EffectiveDates.resolve("next month", null, true));
    assertEquals(Instant.parse("2026-02-09T00:00:00Z"), EffectiveDates.resolve("yesterday", anchor, false).from());
  }
}
