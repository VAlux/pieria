package dev.alvo.pieria.api.response;

import java.time.Instant;

/**
 * A harvester sign-in. {@code status} is {@code pending}, {@code authorized} or {@code failed}. The
 * code fields are null when it failed before a code arrived, and {@code errorClass} and
 * {@code error} are set only when it failed.
 */
public record SourceAuthorizationResponse(String status, String verificationUri, String userCode, String message, Instant expiresAt,
                                          String errorClass, String error) {
}
