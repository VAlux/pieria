package dev.alvo.pieria.audit;

/**
 * Bounded retained body plus integrity metadata for the complete byte stream.
 */
public record CapturedPayload(String body, long bytes, String sha256, boolean truncated) {
}
