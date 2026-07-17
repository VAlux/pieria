package dev.alvo.pieria.tools;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential-backoff-with-jitter math and interrupt-safe sleeping, factored out of retry loops so
 * the backoff computation isn't reimplemented per caller.
 */
public final class Backoff {

  private Backoff() {
  }

  /**
   * Delay before the retry that follows attempt {@code attempt} (1-based):
   * {@code min(maxMs, initialMs * multiplier^(attempt-1))}, randomized by ±{@code jitterFraction}.
   */
  public static long delayMillis(int attempt, long initialMs, double multiplier, long maxMs, double jitterFraction) {
    double base = initialMs * Math.pow(multiplier, attempt - 1);
    double capped = Math.min(base, maxMs);
    if (jitterFraction > 0.0 && capped > 0.0) {
      double factor = 1.0 + ThreadLocalRandom.current().nextDouble(-jitterFraction, jitterFraction);
      capped = Math.max(0.0, capped * factor);
    }
    return Math.round(capped);
  }

  /**
   * Sleeps for {@code millis} (a no-op if {@code millis <= 0}). If interrupted, re-sets the
   * thread's interrupt flag and returns {@code true} so the caller can react (e.g. abort the retry
   * loop) instead of swallowing the interruption.
   */
  public static boolean sleepInterruptibly(long millis) {
    if (millis <= 0) {
      return false;
    }
    try {
      Thread.sleep(millis);
      return false;
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return true;
    }
  }
}
