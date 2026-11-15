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

  public static ConflictException profileCycle(String name, String parent) {
    return name.equals(parent)
      ? new ConflictException("Profile '" + name + "' cannot be its own parent")
      : new ConflictException("Profile '" + parent + "' is inside '" + name + "', so it cannot become its parent");
  }
}
