package dev.alvo.pieria.cli.log;

import dev.alvo.pieria.api.response.TaskLaneProgress;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ProgressReporter}: duration/bar formatting helpers and the non-interactive
 * (piped) rendering path, which must emit plain lines only on a phase change or a new 10% step so
 * captured logs stay readable.
 */
class ProgressReporterTests {

  @Test
  void formatsDurationsHumanReadably() {
    assertThat(Durations.format(45)).isEqualTo("45s");
    assertThat(Durations.format(72)).isEqualTo("1m 12s");
    assertThat(Durations.format(3700)).isEqualTo("1h 1m");
    assertThat(Durations.format(-5)).isEqualTo("0s");
  }

  @Test
  void rendersBarProportionally() {
    assertThat(ProgressReporter.renderBar(0.0, 10)).isEqualTo("[··········]");
    assertThat(ProgressReporter.renderBar(0.5, 10)).isEqualTo("[#####·····]");
    assertThat(ProgressReporter.renderBar(1.0, 10)).isEqualTo("[##########]");
    // Out-of-range fractions are clamped.
    assertThat(ProgressReporter.renderBar(2.0, 10)).isEqualTo("[##########]");
  }

  @Test
  void nonInteractiveEmitsPlainLinesThrottledByPhaseAndStep() {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
    ProgressReporter reporter = new ProgressReporter(false, out, () -> 0L);

    reporter.update(List.of(lane("content", "RUNNING", "extract", 1, 10)));
    reporter.update(List.of(lane("content", "RUNNING", "extract", 1, 10)));
    reporter.update(List.of(lane("content", "RUNNING", "extract", 5, 10)));
    reporter.update(List.of(lane("content", "RUNNING", "verify", 1, 2)));
    reporter.finish();                 // no line in non-interactive mode

    String[] lines = buf.toString(StandardCharsets.UTF_8).trim().split("\\R");
    assertThat(lines).containsExactly(
      "content: extract 10% (1/10) [running] · ETA --",
      "content: extract 50% (5/10) [running] · ETA --",
      "content: verify 50% (1/2) [running] · ETA --");
  }

  @Test
  void interactiveRedrawsInPlaceWithBarAndEta() {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
    ProgressReporter reporter = new ProgressReporter(true, out, () -> 0L);

    reporter.update(List.of(lane("content", "RUNNING", "extract", 5, 10)));
    String rendered = buf.toString(StandardCharsets.UTF_8);
    assertThat(rendered).startsWith("\r");
    assertThat(rendered).contains("50%").contains("extract 5/10").contains("[#####");
  }

  @Test
  void rendersMixedWaitingLaneAndThrottlesEachLaneIndependently() {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
    ProgressReporter reporter = new ProgressReporter(false, out, () -> 0L);

    reporter.update(List.of(
      lane("content", "RUNNING", "extract", 1, 10),
      lane("code", "WAITING", "waiting for content", 2, 2)));
    reporter.update(List.of(
      lane("content", "RUNNING", "extract", 2, 10),
      lane("code", "RUNNING", "summarize", 1, 4)));

    assertThat(buf.toString(StandardCharsets.UTF_8)).contains(
      "content: extract 10%", "code: waiting for content",
      "content: extract 20%", "code: summarize 25%");
  }

  private static TaskLaneProgress lane(String name, String state, String phase, int done, int total) {
    return new TaskLaneProgress(name, state, phase, done, total, 0);
  }
}
