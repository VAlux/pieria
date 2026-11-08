package dev.alvo.pieria.harvester.manifest;

/** What a harvester declares it can do. */
public enum Capability {
  INCREMENTAL("incremental"),
  DELETES("deletes"),
  OAUTH_DEVICE_CODE("oauth-device-code");

  private final String wire;

  Capability(String wire) {
    this.wire = wire;
  }

  public String wire() {
    return wire;
  }

  static Capability fromWire(String wire) {
    for (Capability value : values()) {
      if (value.wire.equals(wire)) {
        return value;
      }
    }
    throw new ManifestException("unknown capability: " + wire);
  }
}
