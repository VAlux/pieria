package dev.alvo.pieria.model;

import dev.alvo.pieria.config.PieriaProperties.Model.Retry;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retry policy for model calls: transient failures are retried up to the attempt cap, deterministic
 * failures are rethrown immediately, and the last failure surfaces once retries are exhausted. Uses a
 * 1ms backoff so the real sleeps are negligible.
 */
class ModelCallRetryTests {

  /** A RuntimeException whose cause is a ConnectException — what {@code isTransient} treats as a blip. */
  private static RuntimeException transientFailure() {
    return new RuntimeException("io error", new ConnectException("connection refused"));
  }

  private static Retry policy(int maxAttempts) {
    return new Retry(maxAttempts, 1, 2, 2.0, 0.0);
  }

  @Test
  void retriesTransientFailureThenReturnsTheEventualSuccess() {
    AtomicInteger calls = new AtomicInteger();
    ModelCallRetry retry = new ModelCallRetry(policy(3));

    String result = retry.execute("extract", () -> {
      if (calls.incrementAndGet() < 3) {
        throw transientFailure();
      }
      return "ok";
    });

    assertThat(result).isEqualTo("ok");
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void stopsAfterMaxAttemptsAndRethrowsTheLastFailure() {
    AtomicInteger calls = new AtomicInteger();
    ModelCallRetry retry = new ModelCallRetry(policy(2));

    assertThatThrownBy(() -> retry.execute("extract", () -> {
      calls.incrementAndGet();
      throw transientFailure();
    })).isInstanceOf(RuntimeException.class)
      .hasCauseInstanceOf(ConnectException.class);

    assertThat(calls.get()).as("total tries == maxAttempts").isEqualTo(2);
  }

  @Test
  void doesNotRetryADeterministicFailure() {
    AtomicInteger calls = new AtomicInteger();
    ModelCallRetry retry = new ModelCallRetry(policy(5));

    assertThatThrownBy(() -> retry.execute("extract", () -> {
      calls.incrementAndGet();
      throw new IllegalArgumentException("bad request");
    })).isInstanceOf(IllegalArgumentException.class);

    assertThat(calls.get()).as("a non-transient failure is not retried").isEqualTo(1);
  }

  @Test
  void maxAttemptsOfOneDisablesRetry() {
    AtomicInteger calls = new AtomicInteger();
    ModelCallRetry retry = new ModelCallRetry(policy(1));

    assertThatThrownBy(() -> retry.execute("extract", () -> {
      calls.incrementAndGet();
      throw transientFailure();
    })).isInstanceOf(RuntimeException.class);

    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void succeedsOnFirstTryWithoutSleeping() {
    AtomicInteger calls = new AtomicInteger();
    ModelCallRetry retry = new ModelCallRetry(policy(3));

    String result = retry.execute("extract", () -> {
      calls.incrementAndGet();
      return "immediate";
    });

    assertThat(result).isEqualTo("immediate");
    assertThat(calls.get()).isEqualTo(1);
  }
}
