package dev.alvo.pieria.domain.code;

import java.util.Locale;

/**
 * The relation labels carried on a {@link CodeEdge}. Stored as the {@link #wire()} form (lower-case,
 * hyphenated) in the {@code code_edges.relation} column.
 */
public enum CodeRelation {
  CALLS,
  REFERENCES,
  IMPORTS,
  EXTENDS,
  IMPLEMENTS,
  DEPENDS_ON,
  TESTS,
  HANDLES_ROUTE;

  /** Canonical wire/storage form, e.g. {@code "depends-on"}. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** Parse a stored/wire value (case-insensitive, hyphen or underscore); throws if unknown. */
  public static CodeRelation fromWire(String value) {
    if (value == null) {
      throw new IllegalArgumentException("relation must not be null");
    }
    return CodeRelation.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
  }
}
