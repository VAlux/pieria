package dev.alvo.pieria.domain;

import dev.alvo.pieria.domain.memory.MemoryType;

import static dev.alvo.pieria.tools.Hash.hash128;
import static dev.alvo.pieria.tools.StringKit.nullToEmpty;

/**
 * Content-addressed identifiers: {@code SHA-256(sessionId + role + content)} truncated
 * to 128 bits, hex-encoded. Re-ingesting the same conversation yields identical ids, making
 * inserts idempotent via {@code INSERT OR IGNORE}.
 */
public final class ContentId {

  private ContentId() {
  }

  /**
   * Id for a message: hashes session, role, and content.
   */
  public static String forMessage(String sessionId, String role, String content) {
    return hash128(nullToEmpty(sessionId), nullToEmpty(role), nullToEmpty(content));
  }

  /**
   * Id for a memory: hashes session, type, and canonical content. Distinct content or type
   * produces a distinct id; identical extracted memories collapse to one row.
   *
   * <p>Backwards-compatible 3-arg form: delegates to the full form with null
   * {@code topicKey}/{@code payload}.
   */
  public static String forMemory(String sessionId, MemoryType type, String content) {
    return forMemory(sessionId, type, content, null, null);
  }

  /**
   * Id for a memory incorporating identity-defining fields: session, type, canonical content,
   * {@code topicKey}, and {@code payload}. Two keyed memories with the same content but distinct
   * topic keys (or distinct payloads) therefore receive distinct ids, while remaining fully
   * deterministic. The 3-arg overload is equivalent to passing null for both extra fields, so the
   * common case where no topic key/payload exists keeps its historical id.
   */
  public static String forMemory(
    String sessionId, MemoryType type, String content, String topicKey, String payload) {
    return hash128(
      nullToEmpty(sessionId),
      type.wire(),
      nullToEmpty(content),
      nullToEmpty(topicKey),
      nullToEmpty(payload));
  }

  /**
   * Id for a graph entity: hashes profile, normalized type, and normalized name. Two entities with
   * the same normalized {@code (type, name)} within a profile collapse to one node, so re-ingest is
   * idempotent. Names/types are expected to be normalized before this call.
   */
  public static String forEntity(String profileId, String type, String normalizedName) {
    return hash128(nullToEmpty(profileId), nullToEmpty(type), nullToEmpty(normalizedName));
  }

  /**
   * Id for a graph edge: hashes profile, source entity id, normalized relation, target entity id,
   * and the originating memory id (provenance). Identical triples grounded in the same memory
   * collapse to one row.
   */
  public static String forEdge(
    String profileId, String sourceEntityId, String relation, String targetEntityId, String memoryId) {
    return hash128(
      nullToEmpty(profileId),
      nullToEmpty(sourceEntityId),
      nullToEmpty(relation),
      nullToEmpty(targetEntityId),
      nullToEmpty(memoryId));
  }
}
