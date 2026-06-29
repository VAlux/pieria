package dev.alvo.pieria.tools;

/**
 * Deterministic token estimation, shared by the daemon (which records savings at ingest/recall
 * time) and the CLI (which renders them). Intentionally a coarse {@code chars / 4} heuristic: it
 * makes no model call, has no native-image footprint, and is provider-agnostic — the impact panel
 * is a <em>relative</em> estimate, not billing-grade accounting. Keeping the divisor in one place
 * means a char count summed in SQL ({@link #fromChars}) and a string measured in Java
 * ({@link #estimate}) always convert the same way.
 */
public final class Tokens {

  /**
   * Average characters per token for the heuristic.
   */
  private static final double CHARS_PER_TOKEN = 4.0;

  private Tokens() {
  }

  /**
   * Estimated token count of {@code text}; {@code 0} for null/empty.
   */
  public static long estimate(String text) {
    return text == null || text.isEmpty() ? 0L : fromChars(text.length());
  }

  /**
   * Estimated token count for a raw character total (e.g. a SQL {@code SUM(LENGTH(content))}).
   */
  public static long fromChars(long chars) {
    return chars <= 0 ? 0L : (long) Math.ceil(chars / CHARS_PER_TOKEN);
  }
}
