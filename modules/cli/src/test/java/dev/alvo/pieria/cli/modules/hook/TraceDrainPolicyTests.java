package dev.alvo.pieria.cli.modules.hook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDrainPolicyTests {

  // A final capture must leave nothing behind, whatever the spool holds.
  @Test
  void aFinalCaptureAlwaysDrains() {
    assertThat(TraceDrainPolicy.shouldDrain(false, 0L, 0, 65_536L, 50)).isTrue();
    assertThat(TraceDrainPolicy.shouldDrain(false, 10L, 1, 65_536L, 50)).isTrue();
  }

  // The whole point: a small spool at the end of a turn keeps accumulating, so a failure in this
  // turn and its fix in the next land in one extraction window.
  @Test
  void anEndOfTurnCaptureUnderThresholdDoesNotDrain() {
    assertThat(TraceDrainPolicy.shouldDrain(true, 1_024L, 3, 65_536L, 50)).isFalse();
  }

  @Test
  void eitherThresholdAloneTriggersAnEndOfTurnDrain() {
    assertThat(TraceDrainPolicy.shouldDrain(true, 70_000L, 3, 65_536L, 50)).isTrue();
    assertThat(TraceDrainPolicy.shouldDrain(true, 1_024L, 60, 65_536L, 50)).isTrue();
  }

  @Test
  void anEmptySpoolNeverDrains() {
    assertThat(TraceDrainPolicy.shouldDrain(true, 0L, 0, 65_536L, 50)).isFalse();
    assertThat(TraceDrainPolicy.shouldDrain(false, 0L, 0, 65_536L, 50)).isTrue();
  }

  @Test
  void zeroThresholdsMakeEveryTurnDrain() {
    assertThat(TraceDrainPolicy.shouldDrain(true, 1L, 1, 0L, 0)).isTrue();
  }
}
