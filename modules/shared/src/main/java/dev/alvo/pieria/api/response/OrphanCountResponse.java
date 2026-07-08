package dev.alvo.pieria.api.response;

/**
 * Cheap dry-run count for orphan adoption: how many active, non-{@code TASK}, edgeless, not-yet-
 * adopted memories a {@code pieria reminisce} run would process. Computed by a plain store query, no
 * model call.
 *
 * @param orphans the number of graph-orphan memories in the profile
 */
public record OrphanCountResponse(long orphans) {
}
