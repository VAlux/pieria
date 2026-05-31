package dev.alvo.pieria.ingestion.model;

import dev.alvo.pieria.domain.memory.MemoryType;

/**
 * A raw candidate memory emitted by an extraction pass, before verification and
 * classification. {@code suggestedType} is the extractor's best guess and may be {@code null};
 * the authoritative type is assigned later by classification.
 *
 * @param content       the candidate declarative statement
 * @param suggestedType the extractor's tentative type, or {@code null} if unclassified
 * @param chunkIndex    index of the source chunk this candidate came from (provenance)
 * @param provenance    free-form provenance hint (e.g. source line range), or {@code null}
 */
public record ExtractedCandidate(
  String content,
  MemoryType suggestedType,
  int chunkIndex,
  String provenance) {
}
