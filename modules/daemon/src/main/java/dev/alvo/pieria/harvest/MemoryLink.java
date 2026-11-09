package dev.alvo.pieria.harvest;

/**
 * Ties an item to a memory built from it. {@code revisionId} is the revision at link time, so a memory
 * the user has since revised can be recognized and left alone.
 */
public record MemoryLink(String memoryId, String assertionId, String revisionId) {
}
