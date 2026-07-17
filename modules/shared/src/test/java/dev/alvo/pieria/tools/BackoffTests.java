package dev.alvo.pieria.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BackoffTests {

  @Test
  void delayGrowsExponentiallyWithoutJitter() {
    long first = Backoff.delayMillis(1, 100, 2.0, 10_000, 0.0);
    long second = Backoff.delayMillis(2, 100, 2.0, 10_000, 0.0);
    long third = Backoff.delayMillis(3, 100, 2.0, 10_000, 0.0);

    assertThat(first).isEqualTo(100);
    assertThat(second).isEqualTo(200);
    assertThat(third).isEqualTo(400);
  }

  @Test
  void delayIsCappedAtMax() {
    long delay = Backoff.delayMillis(10, 100, 2.0, 1_000, 0.0);

    assertThat(delay).isEqualTo(1_000);
  }

  @Test
  void jitterStaysWithinExpectedBounds() {
    for (int i = 0; i < 50; i++) {
      long delay = Backoff.delayMillis(3, 100, 2.0, 10_000, 0.5);
      // base = 400, jitter fraction 0.5 => factor in [0.5, 1.5] => delay in [200, 600]
      assertThat(delay).isBetween(200L, 600L);
    }
  }

  @Test
  void sleepInterruptiblyIsNoOpForNonPositiveMillis() {
    assertThat(Backoff.sleepInterruptibly(0)).isFalse();
    assertThat(Backoff.sleepInterruptibly(-5)).isFalse();
  }

  @Test
  void sleepInterruptiblySleepsAndReturnsFalseWhenUninterrupted() {
    long start = System.nanoTime();
    boolean interrupted = Backoff.sleepInterruptibly(5);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(interrupted).isFalse();
    assertThat(elapsedMs).isGreaterThanOrEqualTo(5);
  }

  @Test
  void sleepInterruptiblyReturnsTrueAndRestoresFlagOnInterruption() throws InterruptedException {
    Thread.currentThread().interrupt();
    boolean interrupted = Backoff.sleepInterruptibly(1_000);

    assertThat(interrupted).isTrue();
    assertThat(Thread.interrupted()).isTrue(); // consumes the flag we asserted, leaves thread clean
  }
}
