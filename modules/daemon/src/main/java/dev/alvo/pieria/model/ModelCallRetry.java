package dev.alvo.pieria.model;

import dev.alvo.pieria.config.PieriaProperties.Model.Retry;
import dev.alvo.pieria.tools.Backoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Wraps a single model-call attempt in bounded retry-with-exponential-backoff, retrying only
 * <em>transient</em> failures (rate limits, 5xx, brief provider outages — see
 * {@link ModelFailures#isTransient}). Deterministic failures (a 400 on a poison chunk, auth/404
 * errors) are rethrown immediately, since an identical retry would fail identically and only delay
 * the real error.
 *
 * <p>The point is that a momentary provider blip no longer aborts a multi-hour onboard: the failing
 * call is retried in place, so the batch completes (and is ledgered) instead of throwing all the way
 * out and forcing the whole source to be re-extracted on the next run. Stateless and thread-safe —
 * shared across the concurrent extraction workers.
 */
public final class ModelCallRetry {

  private static final Logger LOGGER = LoggerFactory.getLogger(ModelCallRetry.class);

  private final Retry policy;

  public ModelCallRetry(Retry policy) {
    this.policy = policy == null ? Retry.DEFAULT : policy;
  }

  /**
   * Sleep, preserving interruption: a cancelled onboard aborts promptly with the last failure.
   */
  private static void sleep(long millis, RuntimeException lastFailure) {
    if (Backoff.sleepInterruptibly(millis)) {
      throw lastFailure;
    }
  }

  /**
   * Run {@code call}, retrying transient failures per the configured policy. On a non-transient
   * failure, or once attempts are exhausted, the last exception is rethrown unchanged so the caller's
   * existing error handling (wrap in {@link ModelUnavailableException}, or a stage-local fallback)
   * behaves exactly as before. {@code stage} names the pipeline stage for the retry log line.
   */
  public <T> T execute(String stage, Supplier<T> call) {
    int attempt = 1;
    while (true) {
      try {
        return call.get();
      } catch (RuntimeException e) {
        if (attempt >= policy.maxAttempts() || !ModelFailures.isTransient(e)) {
          throw e;
        }
        long backoff = backoffMillis(attempt);
        LOGGER.warn("model stage={} transient failure on attempt {}/{}: {} — retrying in {}ms",
          stage, attempt, policy.maxAttempts(), ModelFailures.describe(e), backoff);
        sleep(backoff, e);
        attempt++;
      }
    }
  }

  /**
   * Backoff before the retry that follows attempt {@code n} (1-based):
   * {@code min(maxBackoffMs, initialBackoffMs * multiplier^(n-1))}, randomized by ±jitter.
   */
  private long backoffMillis(int attempt) {
    return Backoff.delayMillis(attempt, policy.initialBackoffMs(), policy.multiplier(),
      policy.maxBackoffMs(), policy.jitter());
  }
}
