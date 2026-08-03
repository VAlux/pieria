package dev.alvo.pieria.api.response;

/**
 * One non-fatal failure captured while running a composite onboarding source. Source numbers are
 * one-based so the daemon log, task progress, REST result, and CLI report all identify the source
 * consistently.
 */
public record OnboardError(
  int sourceNumber,
  String sourceType,
  String errorType,
  String message) {

  public OnboardError {
    sourceType = sourceType == null ? "unknown" : sourceType;
    errorType = errorType == null ? "unknown" : errorType;
    message = message == null || message.isBlank() ? errorType : message;
  }

  public static OnboardError from(int sourceNumber, String sourceType, Throwable failure) {
    String type = failure == null ? "unknown" : failure.getClass().getSimpleName();
    String message = failure == null ? type : failure.getMessage();
    if (message == null || message.isBlank()) {
      message = failure.toString();
    }
    return new OnboardError(sourceNumber, sourceType, type, message);
  }
}
