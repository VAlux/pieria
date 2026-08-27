package dev.alvo.pieria.domain.code;

import java.util.Locale;

/**
 * How confidently a {@link CodeEdge}'s target was resolved.
 *
 * <p>{@link #RESOLVED} edges come from precise within-file binding (or import/inheritance targets
 * resolvable from the file's imports); {@link #HEURISTIC} edges are name-based cross-file guesses
 * that can over-match same-named symbols. Stored lower-cased in the {@code code_edges.confidence}
 * column. The {@link #rank()} ordering drives the {@code minConfidence} filter during traversal
 * ("traverse an edge only when its {@code rank() >= minConfidence.rank()}").
 */
public enum EdgeConfidence {
  HEURISTIC,
  RESOLVED;

  /**
   * Parse a stored/wire value (case-insensitive); throws if unknown.
   */
  public static EdgeConfidence fromWire(String value) {
    if (value == null) {
      throw new IllegalArgumentException("confidence must not be null");
    }
    return EdgeConfidence.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * Higher = more trustworthy; used for the {@code minConfidence} traversal filter.
   */
  public int rank() {
    return ordinal();
  }

  /**
   * Canonical wire/storage form, e.g. {@code "resolved"}.
   */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
