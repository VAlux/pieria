package dev.alvo.pieria.domain.graph;

import dev.alvo.pieria.domain.ContentId;

import java.time.Instant;

/**
 * A graph node extracted from a verified memory: a normalized, profile-scoped entity such as a
 * person, project, tool, file, or concept. The {@code id} is content-addressed over
 * {@code (profileId, type, name)} so re-ingesting the same entity collapses to one row (see
 * {@link ContentId#forEntity}). {@code id} and {@code createdAt} are assigned at store time when
 * null.
 *
 * <p>Names are normalized deterministically in Java (see {@code EntityNormalizer}) before the id is
 * computed; the model never invents ids.
 *
 * @param payload heterogeneous attributes as a JSON string (defaults to {@code "{}"})
 */
public record Entity(
  String id,
  String profileId,
  String type,
  String name,
  String payload,
  Instant createdAt) {

  /**
   * A freshly extracted entity with no id or timestamp yet (assigned at store time).
   */
  public static Entity of(String type, String name, String payload) {
    return new Entity(null, null, type, name, payload == null ? "{}" : payload, null);
  }
}
