package dev.alvo.pieria.tools;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RelativeTimeTests {

  private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

  private String ago(Duration age) {
    return RelativeTime.since(NOW.minus(age), NOW);
  }

  @Test
  void reportsSubMinuteAgesAsJustNow() {
    assertThat(ago(Duration.ZERO)).isEqualTo("just now");
    assertThat(ago(Duration.ofSeconds(59))).isEqualTo("just now");
  }

  @Test
  void treatsFutureInstantsAsJustNow() {
    assertThat(RelativeTime.since(NOW.plusSeconds(120), NOW)).isEqualTo("just now");
  }

  @Test
  void switchesUnitAtEachBoundary() {
    assertThat(ago(Duration.ofMinutes(1))).isEqualTo("1m ago");
    assertThat(ago(Duration.ofMinutes(59))).isEqualTo("59m ago");
    assertThat(ago(Duration.ofHours(1))).isEqualTo("1h ago");
    assertThat(ago(Duration.ofHours(23))).isEqualTo("23h ago");
    assertThat(ago(Duration.ofDays(1))).isEqualTo("1d ago");
    assertThat(ago(Duration.ofDays(59))).isEqualTo("59d ago");
    assertThat(ago(Duration.ofDays(60))).isEqualTo("2mo ago");
  }

  @Test
  void reportsOnlyTheLargestUnit() {
    assertThat(ago(Duration.ofHours(3).plusMinutes(12))).isEqualTo("3h ago");
    assertThat(ago(Duration.ofDays(2).plusHours(7))).isEqualTo("2d ago");
  }
}
