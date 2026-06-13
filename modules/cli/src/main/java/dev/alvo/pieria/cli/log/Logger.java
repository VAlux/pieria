package dev.alvo.pieria.cli.log;

/**
 * Minimal CLI logger. The {@code pieria} CLI is short-lived and its output <em>is</em> the user
 * interface, so this is deliberately tiny: human-facing lines go to stdout ({@link #info}), problems
 * go to stderr ({@link #error}), with slf4j-style {@code {}} placeholder interpolation. No levels,
 * timestamps, or backing framework.
 */
public final class Logger {

  /**
   * Write an interpolated line to stdout, followed by a newline.
   */
  public void info(String message, Object... values) {
    System.out.println(interpolate(message, values));
  }

  /**
   * Write an interpolated message to stdout with no trailing newline. Use for pre-rendered blocks
   * (e.g. NDJSON export) that already control their own line breaks.
   */
  public void print(String message, Object... values) {
    System.out.print(interpolate(message, values));
  }

  /**
   * Write an interpolated line to stderr, followed by a newline.
   */
  public void error(String message, Object... values) {
    System.err.println(interpolate(message, values));
  }

  String interpolate(String message, Object... values) {
    final StringBuilder interpolated = new StringBuilder(message.length());
    int currentValueIndex = 0;

    for (int i = 0; i < message.length(); i++) {
      final char currentChar = message.charAt(i);
      final boolean placeholder = currentChar == '{'
        && i + 1 < message.length()
        && message.charAt(i + 1) == '}';

      if (placeholder && currentValueIndex < values.length) {
        interpolated.append(values[currentValueIndex++]);
        i++;
      } else {
        interpolated.append(currentChar);
      }
    }

    return interpolated.toString();
  }
}
