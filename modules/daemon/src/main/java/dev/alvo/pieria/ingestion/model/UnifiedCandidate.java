package dev.alvo.pieria.ingestion.model;

/**
 * One candidate memory produced by the unified extraction pass: the declarative {@code content}
 * together with its {@link Classification} (type, topic key, interrogative queries, payload),
 * all emitted by a single model call per chunk. Candidates still go through verification;
 * a {@code CORRECT} verdict re-classifies the corrected content.
 *
 * @param content        the candidate declarative statement
 * @param classification the classification emitted alongside the content
 * @param chunkIndex     index of the source chunk this candidate came from (provenance)
 * @param provenance     free-form provenance hint (e.g. the extraction stage), or {@code null}
 */
public record UnifiedCandidate(
  String content,
  Classification classification,
  int chunkIndex,
  String provenance) {
}
