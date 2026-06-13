package dev.alvo.pieria.domain.code;

import java.util.Locale;

/**
 * The kinds of code symbol the index distinguishes. Stored as the {@link #wire()} form (lower-case,
 * hyphenated) in the {@code code_symbols.kind} column.
 */
public enum CodeSymbolKind {
  MODULE,
  PACKAGE,
  CLASS,
  INTERFACE,
  METHOD,
  FUNCTION,
  FIELD,
  ENDPOINT,
  CONFIG_KEY,
  TEST;

  /** Canonical wire/storage form, e.g. {@code "config-key"}. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** Parse a stored/wire value (case-insensitive, hyphen or underscore); throws if unknown. */
  public static CodeSymbolKind fromWire(String value) {
    if (value == null) {
      throw new IllegalArgumentException("symbol kind must not be null");
    }
    return CodeSymbolKind.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
  }
}
