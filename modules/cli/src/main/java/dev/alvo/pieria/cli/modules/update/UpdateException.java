package dev.alvo.pieria.cli.modules.update;

/**
 * Signals a recoverable failure during {@code pieria update} (download, checksum, extraction, or
 * binary swap). The command catches it and maps it to a non-zero exit code with a clean message,
 * rather than letting a raw stack trace reach the user.
 */
public final class UpdateException extends RuntimeException {

  public UpdateException(String message) {
    super(message);
  }

  public UpdateException(String message, Throwable cause) {
    super(message, cause);
  }
}
