package dev.alvo.pieria.domain.code;

import dev.alvo.pieria.domain.ContentId;

/**
 * A directed relation in the precise code graph (call/import/reference/inheritance/…). Unlike the
 * graph {@code Edge}, a {@code CodeEdge} carries a {@link EdgeConfidence} and is provenanced
 * to a <em>file</em> ({@code fileId}), not a memory: it is active while its file is in the index and
 * is replaced wholesale when that file is re-indexed. The {@code id} is content-addressed over
 * {@code (profileId, srcSymbolId, relation, dstRef, confidence)} (see {@link ContentId#forCodeEdge})
 * so re-index is idempotent.
 *
 * @param dstSymbolId resolved target symbol id, or null when only the name is known
 * @param dstRef      target name (always set; the identity component for unresolved targets)
 * @param fileId      the file this edge was extracted from (provenance / replace key)
 */
public record CodeEdge(
  String id,
  String profileId,
  String srcSymbolId,
  CodeRelation relation,
  EdgeConfidence confidence,
  String dstSymbolId,
  String dstRef,
  String fileId) {

  /** A freshly extracted edge with no id yet (assigned at store time). */
  public static CodeEdge of(String srcSymbolId,
                            CodeRelation relation,
                            EdgeConfidence confidence,
                            String dstSymbolId,
                            String dstRef,
                            String fileId) {
    return new CodeEdge(null, null, srcSymbolId, relation, confidence, dstSymbolId, dstRef, fileId);
  }
}
