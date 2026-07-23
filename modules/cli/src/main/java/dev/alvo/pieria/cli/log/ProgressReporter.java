package dev.alvo.pieria.cli.log;

import dev.alvo.pieria.api.response.TaskLaneProgress;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Renders one progress line per task lane, with lane-independent log throttling and ETA. */
public final class ProgressReporter implements ProgressListener {

  private static final int BAR_WIDTH = 28;
  private static final char FILLED = '#';
  private static final char EMPTY = '·';
  private static final String CLEAR_LINE = "[K";

  private final boolean interactive;
  private final PrintStream out;
  private final LongSupplier milliClock;
  private final long startMillis;
  private final Map<String, Seen> seen = new HashMap<>();
  private int renderedLines;

  public ProgressReporter() {
    this(System.console() != null, System.out, System::currentTimeMillis);
  }

  ProgressReporter(boolean interactive, PrintStream out, LongSupplier milliClock) {
    this.interactive = interactive;
    this.out = out;
    this.milliClock = milliClock;
    this.startMillis = milliClock.getAsLong();
  }

  @Override
  public void onProgress(List<TaskLaneProgress> lanes) {
    update(lanes == null ? List.of() : lanes);
  }

  public void update(List<TaskLaneProgress> lanes) {
    long now = milliClock.getAsLong();
    if (interactive) {
      redraw(lanes, now);
      return;
    }
    for (TaskLaneProgress lane : lanes) {
      double fraction = fraction(lane);
      int bucket = (int) (fraction * 10);
      String marker = lane.state() + "\0" + lane.phase();
      Seen previous = seen.get(lane.name());
      if (previous == null || !previous.marker().equals(marker) || bucket > previous.bucket()) {
        seen.put(lane.name(), new Seen(marker, bucket));
        out.println(plain(lane, fraction, now));
      }
    }
  }

  public void finish() {
    if (!interactive || renderedLines == 0) {
      return;
    }
    for (int i = 0; i < renderedLines; i++) {
      out.print('\r' + CLEAR_LINE);
      if (i + 1 < renderedLines) {
        out.print('\n');
      }
    }
    if (renderedLines > 1) {
      out.print("\033[" + (renderedLines - 1) + "A");
    }
    out.flush();
    renderedLines = 0;
  }

  private void redraw(List<TaskLaneProgress> lanes, long now) {
    int lines = Math.max(renderedLines, lanes.size());
    for (int i = 0; i < lines; i++) {
      out.print('\r');
      if (i < lanes.size()) {
        out.print(line(lanes.get(i), lanes.size() > 1, now));
      }
      out.print(CLEAR_LINE);
      if (i + 1 < lines) {
        out.print('\n');
      }
    }
    if (lines > 1) {
      out.print("\033[" + (lines - 1) + "A");
    }
    out.flush();
    renderedLines = lanes.size();
  }

  private String plain(TaskLaneProgress lane, double fraction, long now) {
    if (waitingForContent(lane)) {
      return lane.name() + ": waiting for content";
    }
    long remaining = etaSeconds(lane, now);
    String eta = remaining >= 0 ? Durations.format(remaining) : "--";
    return lane.name() + ": " + phase(lane) + " " + percent(fraction) + "% ("
      + lane.done() + "/" + lane.total() + ") [" + lane.state().toLowerCase() + "] · ETA " + eta;
  }

  private String line(TaskLaneProgress lane, boolean named, long now) {
    if (waitingForContent(lane)) {
      return lane.name() + ": waiting for content";
    }
    double fraction = fraction(lane);
    long remaining = etaSeconds(lane, now);
    String eta = remaining >= 0 ? "ETA " + Durations.format(remaining) : "ETA --";
    String prefix = named ? lane.name() + ": " : "";
    long elapsed = Math.max(0, (now - startMillis) / 1_000L);
    return prefix + renderBar(fraction, BAR_WIDTH) + ' ' + percent(fraction) + "%  "
      + phase(lane) + ' ' + lane.done() + '/' + lane.total() + " · "
      + lane.state().toLowerCase() + " · " + eta + " · elapsed " + Durations.format(elapsed);
  }

  private static boolean waitingForContent(TaskLaneProgress lane) {
    return "code".equals(lane.name()) && "WAITING".equals(lane.state())
      && "waiting for content".equals(lane.phase());
  }

  private static String phase(TaskLaneProgress lane) {
    return lane.phase() == null || lane.phase().isBlank() ? "starting" : lane.phase();
  }

  private static double fraction(TaskLaneProgress lane) {
    return lane.total() > 0 ? Math.min(1.0, (double) lane.done() / lane.total()) : 0.0;
  }

  private static long etaSeconds(TaskLaneProgress lane, long now) {
    if (!"RUNNING".equals(lane.state()) || lane.done() <= 0 || lane.total() <= 0
        || lane.phaseStartedAtEpochMs() <= 0) {
      return -1;
    }
    double elapsed = (now - lane.phaseStartedAtEpochMs()) / 1_000.0;
    if (elapsed <= 0) {
      return -1;
    }
    double rate = lane.done() / elapsed;
    return rate <= 0 ? -1 : Math.max(0, Math.round((lane.total() - lane.done()) / rate));
  }

  static int percent(double fraction) {
    return (int) Math.round(fraction * 100);
  }

  static String renderBar(double fraction, int width) {
    int filled = (int) Math.round(Math.clamp(fraction, 0.0, 1.0) * width);
    var builder = new StringBuilder(width + 2).append('[');
    for (int i = 0; i < width; i++) {
      builder.append(i < filled ? FILLED : EMPTY);
    }
    return builder.append(']').toString();
  }

  private record Seen(String marker, int bucket) {}
}
