package dev.alvo.pieria.model;

/**
 * Thrown when the underlying model provider (Ollama by default) cannot be reached or fails.
 * Mapped to HTTP 503 by the API layer; the message must not leak provider secrets or hosts.
 */
public class ModelUnavailableException extends RuntimeException {

  public ModelUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }

  public ModelUnavailableException(String message) {
    super(message);
  }
}
