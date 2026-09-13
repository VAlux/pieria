package dev.alvo.pieria.domain;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.domain.memory.MemoryType;

import java.time.Instant;

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

  /** Profile-scoped message id used for persisted rows. */
  public static String forMessage(String profileId, String sessionId, String role, String content) {
    return hash128(
      nullToEmpty(profileId),
      nullToEmpty(sessionId),
      nullToEmpty(role),
      nullToEmpty(content));
  }

  /** Profile-scoped memory id used for persisted rows. */
  public static String forMemory(
    String profileId, String sessionId, MemoryType type, String content) {
    return hash128(
      nullToEmpty(profileId),
      nullToEmpty(sessionId),
      type.wire(),
      nullToEmpty(content));
  }

  /**
   * Id for a raw trace event: hashes profile, session, tool, canonical args, status, and the
   * resolved event time. Re-shipping the same trace within a profile collapses to one row, while
   * identical traces in different profiles stay distinct — the same rule messages and memories use.
   *
   * <p>{@code canonicalArgs} is the <em>redacted, path-normalized, full</em> argument string,
   * deliberately not the {@code CommandSignature}. The signature strips flags so that repeated runs
   * of one command share a topic key; using it here would make {@code ./gradlew test} and
   * {@code ./gradlew test --rerun-tasks} the same trace and drop the second.
   */
  public static String forTrace(String profileId,
                                String sessionId,
                                String tool,
                                String canonicalArgs,
                                TraceStatus status,
                                Instant occurredAt) {
    return hash128(
      nullToEmpty(profileId),
      nullToEmpty(sessionId),
      nullToEmpty(tool),
      nullToEmpty(canonicalArgs),
      status == null ? "" : status.wire(),
      occurredAt == null ? "" : occurredAt.toString());
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
