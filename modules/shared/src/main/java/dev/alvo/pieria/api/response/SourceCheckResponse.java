package dev.alvo.pieria.api.response;

/** Whether a source's config and credentials work. {@code errorClass} is null when they do. */
public record SourceCheckResponse(boolean ok, String errorClass, String message) {
}
