package dev.alvo.pieria.task;

import java.time.Instant;

/** Immutable internal snapshot of one task lane. */
public record TaskLaneSnapshot(
  String name,
  TaskLaneState state,
  String phase,
  int done,
  int total,
  Instant phaseStartedAt) {

  TaskLaneSnapshot withState(TaskLaneState next, String nextPhase, int nextDone, int nextTotal) {
    if (state.terminal()) {
      return this;
    }
    Instant phaseStart = nextPhase != null && nextPhase.equals(phase)
      ? phaseStartedAt : Instant.now();
    return new TaskLaneSnapshot(name, next, nextPhase, nextDone, nextTotal, phaseStart);
  }

  TaskLaneSnapshot terminal(TaskLaneState next) {
    return state.terminal() ? this : new TaskLaneSnapshot(name, next, phase, done, total, phaseStartedAt);
  }
}
