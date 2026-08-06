package dev.alvo.pieria.tools;

import java.time.Duration;
import java.time.Instant;

/**
 * Renders an instant as a coarse age relative to now — {@code "just now"}, {@code "5m ago"},
 * {@code "3h ago"}, {@code "2d ago"}, {@code "4mo ago"}.
 *
 * <p>Deliberately coarse: the callers are human- and agent-facing status lines where the useful
 * signal is "is this fresh or stale", not a precise interval. One unit, no composition — never
 * {@code "3h 12m ago"}.
 *
 * <p>Instants in the future render as {@code "just now"} rather than a negative age; a hook reading
 * a timestamp written by another process can legitimately see a small forward skew.
 */
public final class RelativeTime {

  private static final long MINUTES_PER_HOUR = 60L;
  private static final long HOURS_PER_DAY = 24L;
  private static final long DAYS_PER_MONTH = 30L;

  /**
   * Below this many days, report days; at or above it, switch to months.
   */
  private static final long MONTH_THRESHOLD_DAYS = 60L;

  private RelativeTime() {
  }

  /**
   * The age of {@code then} as of {@code now}, in the largest unit that yields a non-zero count.
   *
   * @param then the instant to describe; must not be null
   * @param now  the reference point, normally {@link Instant#now()}
   */
  public static String since(Instant then, Instant now) {
    Duration age = Duration.between(then, now);
    if (age.isNegative() || age.toMinutes() < 1) {
      return "just now";
    }

    long minutes = age.toMinutes();
    if (minutes < MINUTES_PER_HOUR) {
      return minutes + "m ago";
    }

    long hours = age.toHours();
    if (hours < HOURS_PER_DAY) {
      return hours + "h ago";
    }

    long days = age.toDays();
    if (days < MONTH_THRESHOLD_DAYS) {
      return days + "d ago";
    }

    return (days / DAYS_PER_MONTH) + "mo ago";
  }
}
