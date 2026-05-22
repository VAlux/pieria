package dev.alvo.pieria.api.response;

import java.util.List;

/**
 * Result of an ingest: the stored memories and their count.
 */
public record IngestResponse(List<MemoryResponse> memories, int count) {

  public static IngestResponse of(List<MemoryResponse> memories) {
    return new IngestResponse(memories, memories.size());
  }
}
