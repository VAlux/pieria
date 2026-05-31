package dev.alvo.pieria.ingestion.model;

/**
 * Result of verifying one {@link ExtractedCandidate} against the source transcript.
 * When the verdict is {@code CORRECT}, {@code content} holds the corrected statement; for
 * {@code PASS} it echoes the original; for {@code DROP} it is ignored. {@code reason} is captured
 * for logs and tests.
 */
public record VerificationResult(
  VerificationVerdict verdict,
  String content,
  String reason) {
}
