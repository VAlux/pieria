package dev.alvo.pieria.domain.profile;

import java.time.Instant;

/**
 * A named memory store; the unit of organization. Profiles form a tree: {@code parentId} is the id
 * of the profile this one sits under, or {@code null} for a top-level profile. Writes always land in
 * exactly one profile; only recall reads a profile together with everything below it.
 */
public record Profile(String id, String name, Instant createdAt, String parentId) {

  /** A top-level profile. */
  public Profile(String id, String name, Instant createdAt) {
    this(id, name, createdAt, null);
  }
}
