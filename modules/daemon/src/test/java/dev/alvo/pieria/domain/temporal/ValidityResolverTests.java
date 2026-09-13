package dev.alvo.pieria.domain.temporal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValidityResolverTests {
  private static Instant at(String month) { return Instant.parse("2026-" + month + "-01T00:00:00Z"); }
  private static ValidityResolver.Assertion assertion(String id, String from, String to) {
    return new ValidityResolver.Assertion(id, at(from), to == null ? null : at(to), at("01"), id, 1);
  }

  @Test void boundedOverridesRestoreUnderlyingAssertionAndIgnoreArrivalOrder() {
    var alice = assertion("alice", "01", null);
    var bob = assertion("bob", "03", "04");
    var expected = List.of(new ValidityResolver.Segment("alice", at("01"), at("03")),
      new ValidityResolver.Segment("bob", at("03"), at("04")), new ValidityResolver.Segment("alice", at("04"), null));
    assertEquals(expected, ValidityResolver.resolve(List.of(alice, bob)));
    assertEquals(expected, ValidityResolver.resolve(List.of(bob, alice)));
  }

  @Test void nestedOverridesAndGaps() {
    var result = ValidityResolver.resolve(List.of(assertion("a", "01", "06"), assertion("b", "03", "05"),
      assertion("c", "04", "05"), assertion("d", "07", "08")));
    assertEquals(List.of("a", "b", "c", "a", "d"), result.stream().map(ValidityResolver.Segment::revisionId).toList());
    assertEquals(at("06"), result.get(3).to());
    assertEquals(at("07"), result.get(4).from());
  }

  @Test void equalTimeTieIsStableAndBadBoundsRejected() {
    assertEquals(ValidityResolver.resolve(List.of(assertion("a", "01", null), assertion("b", "01", null))),
      ValidityResolver.resolve(List.of(assertion("b", "01", null), assertion("a", "01", null))));
    assertThrows(IllegalArgumentException.class, () -> assertion("x", "03", "03"));
  }

  @Test void timestampDefaultsAreIndependentAndOffsetsNormalize() {
    Instant now = at("05");
    var past = TemporalSnapshot.resolve("2026-03-01T02:00:00+02:00", null, now, 7);
    assertEquals(at("03"), past.asOf());
    assertEquals(now, past.knownAsOf());
    assertEquals(now, TemporalSnapshot.resolve(null, "2026-03-01T00:00:00Z", now, 7).asOf());
    assertEquals(at("06"), TemporalSnapshot.resolve("2026-06-01T00:00:00Z", null, now, 7).asOf());
    assertThrows(IllegalArgumentException.class, () -> TemporalSnapshot.resolve(null, "2026-06-01T00:00:00Z", now, 7));
    assertThrows(IllegalArgumentException.class, () -> TemporalSnapshot.resolve("2026-03-01", null, now, 7));
  }
}
