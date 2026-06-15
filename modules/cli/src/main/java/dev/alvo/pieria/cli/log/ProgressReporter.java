package dev.alvo.pieria.cli.log;

import java.io.PrintStream;
import java.util.function.LongSupplier;

/**
 * Renders live progress for a long-running command. On an interactive terminal it redraws a single
 * in-place line (carriage return + ANSI clear-to-end-of-line) with a bar, percent, phase counts, a
 * per-phase ETA and overall elapsed time. When output is not a TTY (piped/redirected) it degrades to
 * plain lines emitted only on a phase change or each 10% step, so logs stay readable.
 *
 * <p>The ETA is per-phase by design: the pipeline's later phases aren't sized until earlier ones
 * finish, so a single global percentage would be fabricated. Timing is measured client-side from
 * when each phase is first observed.
 */
public final class ProgressReporter implements ProgressListener {

  private static final int BAR_WIDTH = 28;
  private static final char FILLED = '#';
  private static final char EMPTY = '·'; // middle dot
  private static final String CLEAR_LINE = "[K";

  private final boolean interactive;
  private final PrintStream out;
  private final LongSupplier nanoClock;

  private final long startNanos;
  private String currentPhase;
  private long phaseStartNanos;
  private int lastBucket = -1;

  public ProgressReporter() {
    this(System.console() != null, System.out, System::nanoTime);
  }

  ProgressReporter(boolean interactive, PrintStream out, LongSupplier nanoClock) {
    this.interactive = interactive;
    this.out = out;
    this.nanoClock = nanoClock;
    this.startNanos = nanoClock.getAsLong();
  }

  @Override
  public void onProgress(String phase, int done, int total) {
    update(phase, done, total);
  }

  /** Render an update for {@code phase} at {@code done}/{@code total}. */
  public void update(String phase, int done, int total) {
    long now = nanoClock.getAsLong();
    boolean phaseChanged = !phase.equals(currentPhase);
    if (phaseChanged) {
      currentPhase = phase;
      phaseStartNanos = now;
      lastBucket = -1;
    }

    double fraction = total > 0 ? Math.min(1.0, (double) done / total) : 0.0;
    if (interactive) {
      out.print('\r' + line(phase, done, total, fraction, now) + CLEAR_LINE);
      out.flush();
      return;
    }

    int bucket = (int) (fraction * 10);
    if (phaseChanged || bucket > lastBucket) {
      lastBucket = bucket;
      out.println(phase + " " + percent(fraction) + "% (" + done + "/" + total + ")");
    }
  }

  /** Clear the live line (interactive only); the caller prints the final summary afterward. */
  public void finish() {
    if (interactive) {
      out.print('\r' + CLEAR_LINE);
      out.flush();
    }
  }

  private String line(String phase, int done, int total, double fraction, long now) {
    long elapsedSeconds = (now - startNanos) / 1_000_000_000L;
    long remaining = etaSeconds(done, total, now);
    String eta = remaining >= 0 ? "ETA " + formatDuration(remaining) : "ETA --";
    return renderBar(fraction, BAR_WIDTH) + ' ' + percent(fraction) + "%  "
      + phase + ' ' + done + '/' + total + " · " + eta
      + " · elapsed " + formatDuration(elapsedSeconds);
  }

  /** Remaining seconds for the current phase from its observed rate, or -1 when not yet known. */
  private long etaSeconds(int done, int total, long now) {
    double elapsed = (now - phaseStartNanos) / 1_000_000_000.0;
    if (done <= 0 || total <= 0 || elapsed <= 0) {
      return -1;
    }
    double rate = done / elapsed;
    if (rate <= 0) {
      return -1;
    }
    return Math.max(0, Math.round((total - done) / rate));
  }

  private static int percent(double fraction) {
    return (int) Math.round(fraction * 100);
  }

  /** {@code [####······]} with {@code width} cells filled in proportion to {@code fraction}. */
  static String renderBar(double fraction, int width) {
    int filled = (int) Math.round(Math.min(1.0, Math.max(0.0, fraction)) * width);
    StringBuilder b = new StringBuilder(width + 2);
    b.append('[');
    for (int i = 0; i < width; i++) {
      b.append(i < filled ? FILLED : EMPTY);
    }
    return b.append(']').toString();
  }

  /** Human-readable duration: {@code "45s"}, {@code "1m 12s"}, {@code "2h 5m"}. */
  static String formatDuration(long totalSeconds) {
    long s = Math.max(0, totalSeconds);
    if (s < 60) {
      return s + "s";
    }
    long minutes = s / 60;
    long seconds = s % 60;
    if (minutes < 60) {
      return minutes + "m " + seconds + "s";
    }
    long hours = minutes / 60;
    return hours + "h " + (minutes % 60) + "m";
  }
}
