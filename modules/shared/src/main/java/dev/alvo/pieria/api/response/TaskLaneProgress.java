package dev.alvo.pieria.api.response;

/** Progress for one independently scheduled lane of an asynchronous task. */
public record TaskLaneProgress(
  String name,
  String state,
  String phase,
  int done,
  int total,
  long phaseStartedAtEpochMs) {
}
