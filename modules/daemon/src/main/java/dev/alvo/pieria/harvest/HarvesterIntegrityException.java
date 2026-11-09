package dev.alvo.pieria.harvest;

/** An installed harvester's entrypoint no longer matches the checksum pinned at install. */
public final class HarvesterIntegrityException extends RuntimeException {

  public HarvesterIntegrityException(String message) {
    super(message);
  }
}
