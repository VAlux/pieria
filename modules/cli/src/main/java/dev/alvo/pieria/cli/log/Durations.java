package dev.alvo.pieria.cli.log;

/**
 * Shared human-readable duration formatting so the live progress bar and the {@code pieria task}
 * listing render elapsed times and ETAs identically.
 */
public final class Durations {

  private Durations() {
  }

  /**
   * Human-readable duration: {@code "45s"}, {@code "1m 12s"}, {@code "2h 5m"}.
   */
  public static String format(long totalSeconds) {
    long sec = Math.max(0, totalSeconds);
    if (sec < 60) {
      return sec + "s";
    }
    long minutes = sec / 60;
    long seconds = sec % 60;
    if (minutes < 60) {
      return minutes + "m " + seconds + "s";
    }
    long hours = minutes / 60;
    return hours + "h " + (minutes % 60) + "m";
  }
}
