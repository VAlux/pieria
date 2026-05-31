package dev.alvo.pieria.tools;

public final class StringKit {
  private StringKit() {

  }

  public static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
