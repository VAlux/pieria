package dev.alvo.pieria.cli.log;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ProgressReporter}: duration/bar formatting helpers and the non-interactive
 * (piped) rendering path, which must emit plain lines only on a phase change or a new 10% step so
 * captured logs stay readable.
 */
class ProgressReporterTests {

  @Test
  void formatsDurationsHumanReadably() {
    assertThat(ProgressReporter.formatDuration(45)).isEqualTo("45s");
    assertThat(ProgressReporter.formatDuration(72)).isEqualTo("1m 12s");
    assertThat(ProgressReporter.formatDuration(3700)).isEqualTo("1h 1m");
    assertThat(ProgressReporter.formatDuration(-5)).isEqualTo("0s");
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

    reporter.update("extract", 1, 10); // 10% — first tick of the phase
    reporter.update("extract", 1, 10); // same bucket — suppressed
    reporter.update("extract", 5, 10); // 50% — new bucket
    reporter.update("verify", 1, 2);   // phase change — always emitted
    reporter.finish();                 // no line in non-interactive mode

    String[] lines = buf.toString(StandardCharsets.UTF_8).trim().split("\\R");
    assertThat(lines).containsExactly(
      "extract 10% (1/10)",
      "extract 50% (5/10)",
      "verify 50% (1/2)");
  }

  @Test
  void interactiveRedrawsInPlaceWithBarAndEta() {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
    ProgressReporter reporter = new ProgressReporter(true, out, () -> 0L);

    reporter.update("extract", 5, 10);
    String rendered = buf.toString(StandardCharsets.UTF_8);
    assertThat(rendered).startsWith("\r");
    assertThat(rendered).contains("50%").contains("extract 5/10").contains("[#####");
  }
}
