package dev.alvo.pieria.tools.os;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Quotes values for embedding in a PowerShell {@code -Command} string.
 *
 * <p>Single-quoted PowerShell strings are literal: no variable expansion, no backtick escapes, and
 * the only character needing attention is {@code '} itself, which is escaped by doubling. That makes
 * them the right choice for Windows paths — {@code C:\Users\First Last\...} carries both spaces and
 * backslashes, and a double-quoted string would treat {@code $} and {@code `} as syntax.
 */
public final class PowerShellQuoting {

  private PowerShellQuoting() {
  }

  /**
   * Wrap {@code value} as a PowerShell single-quoted literal, doubling any embedded quote.
   */
  public static String singleQuote(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  /**
   * Render {@code values} as a PowerShell array literal, e.g. {@code @('a','b')}. An empty list
   * yields {@code @()}, which cmdlets accept where a populated array would go.
   */
  public static String array(List<String> values) {
    return values.stream()
      .map(PowerShellQuoting::singleQuote)
      .collect(Collectors.joining(",", "@(", ")"));
  }
}
