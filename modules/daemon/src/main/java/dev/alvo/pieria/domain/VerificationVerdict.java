package dev.alvo.pieria.domain;

import java.util.Locale;

/**
 * Outcome of verifying an extracted candidate against the source transcript:
 * {@code PASS} (kept as-is), {@code CORRECT} (kept with corrected content), or {@code DROP}
 * (unsupported/ambiguous, discarded).
 */
public enum VerificationVerdict {
  PASS,
  CORRECT,
  DROP;

  /**
   * Parse a model/wire value (case-insensitive); throws {@link IllegalArgumentException} if unknown.
   */
  public static VerificationVerdict fromWire(String value) {
    if (value == null) {
      throw new IllegalArgumentException("verification verdict must not be null");
    }
    return VerificationVerdict.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }
}
