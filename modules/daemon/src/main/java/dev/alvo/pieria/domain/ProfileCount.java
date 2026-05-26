package dev.alvo.pieria.domain;

/**
 * A profile paired with the number of active (non-superseded) memories it holds.
 * Produced by the storage layer for the profile-listing endpoint.
 */
public record ProfileCount(Profile profile, long activeCount) {
}
