package dev.alvo.pieria.domain;

import dev.alvo.pieria.domain.memory.MemoryType;

import static dev.alvo.pieria.tools.Hash.hash128;
import static dev.alvo.pieria.tools.StringKit.nullToEmpty;

/**
 * Content-addressed identifiers truncated to 128 bits and hex-encoded. Persisted message and
 * memory ids include the owning profile id so identical content can coexist in different profiles
 * while re-ingestion within one profile remains idempotent.
 */
public final class ContentId {

  private ContentId() {
  }

  /** Legacy unscoped message id, retained to recognize rows written before profile namespacing. */
  public static String forMessage(String sessionId, String role, String content) {
    return hash128(nullToEmpty(sessionId), nullToEmpty(role), nullToEmpty(content));
  }

  /**
   * Profile-scoped message id used for new persisted rows. The three-argument form remains available
   * to recognize ids written before profile namespacing was introduced.
   */
  public static String forMessage(String profileId, String sessionId, String role, String content) {
    return hash128(
      nullToEmpty(profileId),
      nullToEmpty(sessionId),
      nullToEmpty(role),
      nullToEmpty(content));
  }

  /**
   * Legacy unscoped memory id, retained to recognize rows written before profile namespacing.
   *
   * <p>Backwards-compatible 3-arg form: delegates to the full form with null
   * {@code topicKey}/{@code payload}.
   */
  public static String forMemory(String sessionId, MemoryType type, String content) {
    return forMemory(sessionId, type, content, null, null);
  }

  /**
   * Profile-scoped memory id used for new persisted rows. The three-argument form remains available
   * to recognize ids written before profile namespacing was introduced.
   */
  public static String forMemory(
    String profileId, String sessionId, MemoryType type, String content) {
    return hash128(
      nullToEmpty(profileId),
      nullToEmpty(sessionId),
      type.wire(),
      nullToEmpty(content));
  }

  /**
   * Legacy unscoped id incorporating identity-defining fields: session, type, canonical content,
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

  /**
   * Id for a code module: hashes profile and the repo-relative module path. Path-stable so a module
   * collapses to one row across re-index.
   */
  public static String forCodeModule(String profileId, String path) {
    return hash128(nullToEmpty(profileId), nullToEmpty(path));
  }

  /**
   * Id for a code file: hashes profile and the repo-relative path only — deliberately
   * <em>path-stable</em> (not content-versioned), so the file keeps one id across edits and its
   * symbols/edges can foreign-key it while {@code replaceFileIndex} re-indexes contents in place.
   * The content version is tracked in the {@code content_hash} column, not the id.
   */
  public static String forCodeFile(String profileId, String repoRelPath) {
    return hash128(nullToEmpty(profileId), nullToEmpty(repoRelPath));
  }

  /**
   * Id for a code symbol: hashes profile, owning file id, kind, qualified name, and signature. A
   * changed signature yields a new id (and overloads stay distinct), while an unchanged declaration
   * keeps its id across re-index.
   */
  public static String forCodeSymbol(
    String profileId, String fileId, String kind, String qualifiedName, String signature) {
    return hash128(
      nullToEmpty(profileId),
      nullToEmpty(fileId),
      nullToEmpty(kind),
      nullToEmpty(qualifiedName),
      nullToEmpty(signature));
  }

  /**
   * Id for a code edge: hashes profile, source symbol id, relation, target reference (name), and
   * confidence. Identical edges from the same file collapse to one row; the same target reached at
   * different confidence stays distinct.
   */
  public static String forCodeEdge(
    String profileId, String srcSymbolId, String relation, String dstRef, String confidence) {
    return hash128(
      nullToEmpty(profileId),
      nullToEmpty(srcSymbolId),
      nullToEmpty(relation),
      nullToEmpty(dstRef),
      nullToEmpty(confidence));
  }
}
