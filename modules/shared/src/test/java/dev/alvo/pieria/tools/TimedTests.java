package dev.alvo.pieria.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TimedTests {

  @Test
  void measureSupplierReturnsValueAndNonNegativeDuration() {
    Timed<String> timed = Timed.measure(() -> "result");

    assertThat(timed.value()).isEqualTo("result");
    assertThat(timed.millis()).isGreaterThanOrEqualTo(0L);
  }

  @Test
  void measureSupplierPropagatesNullValue() {
    Timed<String> timed = Timed.measure(() -> null);

    assertThat(timed.value()).isNull();
    assertThat(timed.millis()).isGreaterThanOrEqualTo(0L);
  }

  @Test
  void measureRunnableRunsWorkAndYieldsVoidValue() {
    AtomicBoolean ran = new AtomicBoolean(false);

    Timed<Void> timed = Timed.measure(() -> ran.set(true));

    assertThat(ran).isTrue();
    assertThat(timed.value()).isNull();
    assertThat(timed.millis()).isGreaterThanOrEqualTo(0L);
  }

  @Test
  void elapsedMillisMeasuresFromNanoReading() {
    long start = System.nanoTime();

    assertThat(Timed.elapsedMillis(start)).isGreaterThanOrEqualTo(0L);
  }
}
