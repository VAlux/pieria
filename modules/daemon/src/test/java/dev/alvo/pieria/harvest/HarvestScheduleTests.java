package dev.alvo.pieria.harvest;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HarvestScheduleTests {

  private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
  private static final Duration MAX = Duration.ofHours(24);

  @Test
  void successWaitsTheInterval() {
    assertThat(HarvestSchedule.nextRunAt(NOW, 3600, 0, null, MAX, 0.0)).isEqualTo(NOW.plusSeconds(3600));
  }

  @Test
  void jitterAddsUpToTenPercent() {
    assertThat(HarvestSchedule.nextRunAt(NOW, 3600, 0, null, MAX, 1.0)).isEqualTo(NOW.plusSeconds(3960));
  }

  @Test
  void failuresBackOffExponentially() {
    assertThat(HarvestSchedule.nextRunAt(NOW, 60, 1, null, MAX, 0.5)).isEqualTo(NOW.plusSeconds(120));
    assertThat(HarvestSchedule.nextRunAt(NOW, 60, 3, null, MAX, 0.5)).isEqualTo(NOW.plusSeconds(480));
  }

  @Test
  void backoffIsCapped() {
    assertThat(HarvestSchedule.nextRunAt(NOW, 3600, 10, null, MAX, 0.0)).isEqualTo(NOW.plus(MAX));
  }

  @Test
  void retryAfterWinsWhenLonger() {
    assertThat(HarvestSchedule.nextRunAt(NOW, 60, 1, 900L, MAX, 0.0)).isEqualTo(NOW.plusSeconds(900));
  }

  @Test
  void retryAfterDoesNotShortenBackoff() {
    assertThat(HarvestSchedule.nextRunAt(NOW, 600, 1, 5L, MAX, 0.0)).isEqualTo(NOW.plusSeconds(1200));
  }

  @Test
  void hugeFailureCountDoesNotOverflow() {
    assertThat(HarvestSchedule.nextRunAt(NOW, 3600, 500, null, MAX, 0.0)).isEqualTo(NOW.plus(MAX));
  }
}
