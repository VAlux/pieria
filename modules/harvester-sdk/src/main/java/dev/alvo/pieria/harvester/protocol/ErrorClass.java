package dev.alvo.pieria.harvester.protocol;

import java.util.Locale;

/** Why a harvester failed, which decides how the daemon retries it. */
public enum ErrorClass {
  AUTH, RATE_LIMITED, TRANSIENT, CONFIG, FATAL;

  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static ErrorClass fromWire(String wire) {
    for (ErrorClass value : values()) {
      if (value.wire().equals(wire)) {
        return value;
      }
    }
    throw new ProtocolViolation("unknown error class: " + wire);
  }
}
