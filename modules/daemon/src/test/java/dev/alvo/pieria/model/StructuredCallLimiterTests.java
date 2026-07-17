package dev.alvo.pieria.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredCallLimiterTests {

  @Test
  void capsAggregateConcurrentAttemptsAndReleasesAfterFailure() throws Exception {
    StructuredCallLimiter limiter = new StructuredCallLimiter(2);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        futures.add(executor.submit(() -> limiter.execute(() -> {
          int now = active.incrementAndGet();
          maximum.accumulateAndGet(now, Math::max);
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            active.decrementAndGet();
          }
          return 1;
        })));
      }
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(maximum.get()).isEqualTo(2);
      release.countDown();
      for (Future<Integer> future : futures) {
        assertThat(future.get()).isEqualTo(1);
      }
    }

    try {
      limiter.execute(() -> { throw new IllegalStateException("boom"); });
    } catch (IllegalStateException expected) {
      // expected
    }
    assertThat(limiter.availablePermits()).isEqualTo(2);
  }

  @Test
  void interruptionWhileQueuedIsPreserved() throws Exception {
    StructuredCallLimiter limiter = new StructuredCallLimiter(1);
    CountDownLatch holderEntered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = Thread.startVirtualThread(() -> limiter.execute(() -> {
      holderEntered.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return null;
    }));
    assertThat(holderEntered.await(2, TimeUnit.SECONDS)).isTrue();

    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
    Thread queued = Thread.startVirtualThread(() -> {
      try {
        limiter.execute(() -> null);
      } catch (Throwable e) {
        failure.set(e);
        interrupted.set(Thread.currentThread().isInterrupted());
      }
    });
    queued.interrupt();
    queued.join(2_000);
    release.countDown();
    holder.join(2_000);

    assertThat(failure.get()).isInstanceOf(ModelUnavailableException.class);
    assertThat(interrupted.get()).isTrue();
  }
}
