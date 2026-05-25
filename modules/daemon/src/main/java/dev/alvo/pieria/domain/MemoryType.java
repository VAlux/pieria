package dev.alvo.pieria.domain;

import java.util.Locale;

/**
 * The four memory classes. Stored lower-cased in the {@code memories.type} column.
 */
public enum MemoryType {
  FACT,
  EVENT,
  INSTRUCTION,
  TASK;

  /**
   * Parse a stored/wire value (case-insensitive); throws {@link IllegalArgumentException} if unknown.
   */
  public static MemoryType fromWire(String value) {
    if (value == null) {
      throw new IllegalArgumentException("memory type must not be null");
    }
    return MemoryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * Canonical wire/storage form, e.g. {@code "fact"}.
   */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
