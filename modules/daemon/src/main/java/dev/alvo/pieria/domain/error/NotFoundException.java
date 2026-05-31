package dev.alvo.pieria.domain.error;

/**
 * A profile- or memory-scoped resource was not found. Mapped to HTTP 404 by the API layer.
 */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String message) {
    super(message);
  }

  public static NotFoundException profile(String name) {
    return new NotFoundException("No profile named '" + name + "'");
  }

  public static NotFoundException memory(String id) {
    return new NotFoundException("No memory with id '" + id + "'");
  }
}
