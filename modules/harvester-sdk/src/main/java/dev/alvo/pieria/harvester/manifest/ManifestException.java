package dev.alvo.pieria.harvester.manifest;

/** A {@code harvester.toml} is missing, unreadable or invalid. */
public final class ManifestException extends RuntimeException {

  public ManifestException(String message) {
    super(message);
  }

  public ManifestException(String message, Throwable cause) {
    super(message, cause);
  }
}
