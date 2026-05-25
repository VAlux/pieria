package dev.alvo.pieria.domain;

/**
 * A pre-computed temporal fact injected into the synthesis prompt.
 * Date math and durations are resolved deterministically in Java — never by the model — and
 * handed to synthesis as ready-made statements.
 *
 * @param description what was computed, e.g. {@code "days since 2026-01-01"}
 * @param value       the deterministic result, e.g. {@code "143 days"}
 */
public record TemporalFact(String description, String value) {

  /**
   * Human-readable single line for the synthesis prompt, e.g. {@code "days since 2026-01-01: 143 days"}.
   */
  public String render() {
    return description + ": " + value;
  }
}
