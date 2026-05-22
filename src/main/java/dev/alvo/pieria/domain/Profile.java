package dev.alvo.pieria.domain;

import java.time.Instant;

/**
 * A named memory store; the unit of organization (SPEC 1.3).
 */
public record Profile(String id, String name, Instant createdAt) {
}
