package dev.alvo.pieria.ingestion;

import dev.alvo.pieria.domain.memory.Memory;

import java.util.List;

/**
 * Detailed internal ingest outcome used by onboarding observability.
 */
public record IngestionResult(List<Memory> memories, int graphDeferred) {
  public IngestionResult {
    memories = memories == null ? List.of() : List.copyOf(memories);
  }
}
