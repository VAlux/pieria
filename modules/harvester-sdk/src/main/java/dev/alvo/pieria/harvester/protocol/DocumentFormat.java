package dev.alvo.pieria.harvester.protocol;

import java.util.Locale;

/** How a document's text is written. */
public enum DocumentFormat {
  MARKDOWN, TEXT;

  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static DocumentFormat fromWire(String wire) {
    for (DocumentFormat value : values()) {
      if (value.wire().equals(wire)) {
        return value;
      }
    }
    throw new ProtocolViolation("unknown document format: " + wire);
  }
}
