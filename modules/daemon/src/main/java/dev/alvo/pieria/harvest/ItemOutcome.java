package dev.alvo.pieria.harvest;

/** What happened to one item: whether it differed from what was stored, and what that produced. */
public record ItemOutcome(boolean changed, int memoriesStored, long chars, String sessionId) {
}
