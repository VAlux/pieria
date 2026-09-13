package dev.alvo.pieria.api.response;

import java.time.Instant;

/** Selected resolved segment and its immutable assertion provenance. */
public record TemporalValidity(String assertionId, String revisionId, Instant validFrom, Instant validTo,
                               Instant recordedFrom, Instant recordedTo, boolean approximate,
                               Instant statedAt, String sourceOccurrence, String factGroup, String sourcePayload) {}
