package dev.alvo.pieria.tools;

public final class StringKit {
  private StringKit() {

  }

  public static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /**
   * Wrap {@code value} in double quotes when it contains a space and is not already quoted.
   *
   * <p>For values that end up inside a single command string that a consumer splits on
   * whitespace — a harness hook command, a service definition. A Windows install path is
   * routinely {@code C:\Users\First Last\...}, so leaving this off breaks for every user
   * whose account name contains a space.
   */
  public static String quoteIfSpaced(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    boolean quoted = value.startsWith("\"") && value.endsWith("\"");
    return quoted || !value.contains(" ") ? value : "\"" + value + "\"";
  }
}
