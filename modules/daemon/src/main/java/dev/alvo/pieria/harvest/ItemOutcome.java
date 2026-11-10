package dev.alvo.pieria.harvest;

/** What happened to one item: whether it differed from what was stored, and what that produced. */
/** @param complete false when the model's output couldn't be read, so the item must be reported again */
public record ItemOutcome(boolean changed, int memoriesStored, long chars, String sessionId, boolean complete) {
}
