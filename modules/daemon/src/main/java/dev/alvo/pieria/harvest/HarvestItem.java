package dev.alvo.pieria.harvest;

/**
 * What the daemon knows about one item at a source.
 *
 * @param prefixHash hash of the thread's first {@code turnCount} turns; null for documents
 */
public record HarvestItem(String sourceId, String externalId, String sessionId, String versionHash, String prefixHash,
                          int turnCount, String lastSeenRun) {
}
