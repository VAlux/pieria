package dev.alvo.pieria.ingestion.model;

/**
 * A row drained from the vectorization outbox: the id of a memory awaiting embedding
 * and the number of attempts so far (for retry/backoff bookkeeping).
 */
public record OutboxEntry(
  String memoryId,
  int attempts) {
}
