package dev.alvo.pieria.harvest;

import dev.alvo.pieria.harvester.protocol.ErrorClass;

import java.time.Instant;
import java.util.Locale;

/**
 * Where a harvester sign-in stands. The code fields are null when it failed before a code arrived;
 * {@code errorClass} and {@code error} are set only when it failed.
 */
public record AuthorizationState(Status status, String verificationUri, String userCode, String message, Instant expiresAt,
                                 ErrorClass errorClass, String error) {

  public enum Status {
    PENDING, AUTHORIZED, FAILED;

    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  static AuthorizationState failed(ErrorClass errorClass, String error) {
    return new AuthorizationState(Status.FAILED, null, null, null, null, errorClass, error);
  }

  AuthorizationState withFailure(ErrorClass errorClass, String error) {
    return new AuthorizationState(Status.FAILED, verificationUri, userCode, message, expiresAt, errorClass, error);
  }

  AuthorizationState authorized() {
    return new AuthorizationState(Status.AUTHORIZED, verificationUri, userCode, message, expiresAt, null, null);
  }
}
