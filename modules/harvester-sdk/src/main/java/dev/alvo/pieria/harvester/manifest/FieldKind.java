package dev.alvo.pieria.harvester.manifest;

import java.util.Locale;

/** The kind of a configuration field, which decides how forms and prompts render it. */
public enum FieldKind {
  STRING, SECRET, INT, BOOL, ENUM, LIST;

  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  static FieldKind fromWire(String wire) {
    for (FieldKind value : values()) {
      if (value.wire().equals(wire)) {
        return value;
      }
    }
    throw new ManifestException("unknown field kind: " + wire);
  }
}
