package dev.alvo.pieria.api.request;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * One inbound tool call: what ran, what it produced, and how it ended.
 *
 * <p>Both timestamps are optional because not every harness reports them. The daemon resolves the
 * event time as {@code endedAt} → {@code startedAt} → its own receipt clock, and counts the
 * receipt-clock case, so a trace with no timestamps is never silently indistinguishable from one
 * that genuinely ran at ingest time.
 *
 * <p>{@code output} and {@code error} are expected to arrive already redacted and capped by the
 * capturing hook; the daemon redacts again on receipt so a direct API caller is covered too.
 */
public record TraceEventDto(
  @NotBlank String tool,
  String args,
  String output,
  TraceStatus status,
  Integer exitCode,
  String error,
  Instant startedAt,
  Instant endedAt) {

  public TraceEventDto {
    status = status == null ? TraceStatus.UNKNOWN : status;
  }
}
