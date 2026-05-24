package dev.alvo.pieria.api.response;

/**
 * Small uniform error body. Must never carry filesystem paths or provider secrets.
 *
 * @param error   short machine-friendly code (e.g. {@code "not_found"})
 * @param message human-readable, sanitized description
 */
public record ErrorResponse(String error, String message) {
}
