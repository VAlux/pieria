package dev.alvo.pieria.harvest;

import java.time.Duration;
import java.time.Instant;

/** When a source should next run. */
final class HarvestSchedule {

  private static final double MAX_JITTER_FRACTION = 0.1;

  private HarvestSchedule() {
  }

  /**
   * @param consecutiveFailures failures including this one; zero after a success
   * @param retryAfterSeconds   how long the source asked to be left alone; may be null
   * @param jitter              a value in [0, 1), spreading successful runs so sources don't align
   */
  static Instant nextRunAt(Instant now, long intervalSeconds, int consecutiveFailures, Long retryAfterSeconds,
                           Duration maxBackoff, double jitter) {
    if (consecutiveFailures == 0) {
      return now.plusMillis(Math.round(intervalSeconds * 1000 * (1 + MAX_JITTER_FRACTION * jitter)));
    }
    double backoff = Math.min(intervalSeconds * Math.pow(2, consecutiveFailures), maxBackoff.toSeconds());
    return now.plusSeconds(Math.max((long) backoff, retryAfterSeconds == null ? 0 : retryAfterSeconds));
  }
}
