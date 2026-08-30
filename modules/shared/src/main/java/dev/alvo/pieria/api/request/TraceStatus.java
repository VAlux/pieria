package dev.alvo.pieria.api.request;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * How a tool call ended. Stored lower-cased in trace payloads, mirroring
 * {@code MemoryType}'s wire convention.
 *
 * <p>Parsing is deliberately lenient where {@code MemoryType.fromWire} throws: a harness that
 * reports a status Pieria does not recognize should degrade to {@link #UNKNOWN}, not fail the
 * whole ingest. An unrecognized status still carries the command, which is most of the signal.
 */
public enum TraceStatus {
  SUCCESS,
  FAILURE,
  UNKNOWN;

  /** Parse a wire value case-insensitively; unknown, blank, and null all yield {@link #UNKNOWN}. */
  @JsonCreator
  public static TraceStatus fromWire(String value) {
    if (value == null || value.isBlank()) {
      return UNKNOWN;
    }
    try {
      return TraceStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return UNKNOWN;
    }
  }

  /** Canonical wire/storage form, e.g. {@code "failure"}. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
