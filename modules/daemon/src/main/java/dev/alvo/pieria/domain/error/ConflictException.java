package dev.alvo.pieria.domain.error;

/**
 * A create request collided with an already-existing resource. Mapped to HTTP 409 by the API layer.
 */
public class ConflictException extends RuntimeException {

  public ConflictException(String message) {
    super(message);
  }

  public static ConflictException profileExists(String name) {
    return new ConflictException("A profile named '" + name + "' already exists");
  }
}
