package dev.alvo.pieria.domain;

/**
 * A row drained from the vectorization outbox (SPEC 6.7): the id of a memory awaiting embedding
 * and the number of attempts so far (for retry/backoff bookkeeping).
 */
public record OutboxEntry(
  String memoryId,
  int attempts) {
}
