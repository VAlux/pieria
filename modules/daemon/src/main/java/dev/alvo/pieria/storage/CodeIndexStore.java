package dev.alvo.pieria.storage;

import dev.alvo.pieria.domain.code.CodeEdge;
import dev.alvo.pieria.domain.code.CodeFile;
import dev.alvo.pieria.domain.code.CodeModule;
import dev.alvo.pieria.domain.code.CodeSymbol;
import dev.alvo.pieria.domain.code.EdgeConfidence;

import java.util.List;
import java.util.Optional;

/**
 * Persistence seam for the Phase 13 source-code intelligence substrate. A sibling to
 * {@link MemoryStore} (not part of it, to keep that interface focused), implemented over the same
 * SQLite datasource so writes participate in the same Spring transaction when an orchestrating
 * service method is {@code @Transactional} — the atomic file replace, the derived-memory write, and
 * the {@code Entity}/{@code Edge} projection then commit together.
 */
public interface CodeIndexStore {

  /** Upsert a module (insert-or-ignore on its content-addressed id). */
  CodeModule upsertCodeModule(String profileId, CodeModule module);

  /** Upsert a file row (insert-or-replace so content_hash/loc/indexedAt refresh in place). */
  CodeFile upsertCodeFile(String profileId, CodeFile file);

  /** Upsert a symbol (insert-or-ignore on its content-addressed id). */
  CodeSymbol upsertCodeSymbol(String profileId, CodeSymbol symbol);

  /** Upsert an edge (insert-or-ignore on its content-addressed id). */
  CodeEdge upsertCodeEdge(String profileId, CodeEdge edge);

  /**
   * The stored {@code content_hash} for a file path, or empty when the path is not yet indexed.
   * The daemon compares this to the incoming hash to skip unchanged files.
   */
  Optional<String> fileContentHash(String profileId, String repoRelPath);

  /**
   * Whether the stored substrate for this file can produce a derived {@code code:file:} memory:
   * at least one symbol, or at least one relation projected by the code indexer. Used to distinguish
   * a legitimately memory-less unchanged file from an incomplete index that needs repair.
   */
  default boolean hasRecallableFileStructure(String profileId, String repoRelPath) {
    return false;
  }

  /**
   * Atomically re-index one file: upsert the file row, delete its prior symbols and edges, and
   * insert the new sets — all in one transaction. Symbol/edge {@code fileId}/{@code profileId} are
   * filled from {@code file} when null.
   */
  void replaceFileIndex(String profileId, CodeFile file, List<CodeSymbol> symbols, List<CodeEdge> edges);

  /** FTS over {@code code_symbols_fts}; matched query is sanitized so it cannot raise an FTS error. */
  List<CodeSymbol> searchSymbolsFts(String profileId, String matchQuery, int limit);

  /** Symbols whose {@code name} is in {@code names} (used to seed the code-graph channel). */
  List<CodeSymbol> findSymbolsByName(String profileId, List<String> names, int limit);

  /** Symbols whose {@code qualified_name} is in {@code qualifiedNames}. */
  List<CodeSymbol> findSymbolsByQualifiedName(String profileId, List<String> qualifiedNames, int limit);

  /** Symbols by id, preserving the input order (used to resolve a traversal back to symbols). */
  List<CodeSymbol> findSymbolsByIds(String profileId, List<String> ids, int limit);

  /**
   * Expand the code-edge neighborhood of {@code seedSymbolIds} up to {@code depth} hops, bounded by
   * {@code fanout} newly-discovered symbols per hop, traversing only edges whose confidence rank is
   * {@code >= minConfidence.rank()} and which resolve to a known symbol. Returns reached symbol ids
   * in BFS order (seeds first), deduped.
   */
  List<String> symbolNeighborhood(
    String profileId, List<String> seedSymbolIds, int depth, int fanout, EdgeConfidence minConfidence);

  /**
   * All edges touching any of {@code symbolIds} (as source or resolved target), each joined with
   * its endpoint symbols, filtered to confidences {@code >= minConfidence.rank()}, resolved edges
   * first in deterministic order, capped at {@code limit}. Feeds the ephemeral code-graph evidence
   * of recall (relation-general: callers/callees, extends, implements, references alike).
   */
  List<EdgeEvidence> findEdgesTouching(
    String profileId, List<String> symbolIds, EdgeConfidence minConfidence, int limit);

  /** A code edge with its hydrated endpoints; {@code dst} is null when the target is unresolved. */
  record EdgeEvidence(CodeEdge edge, CodeSymbol src, CodeSymbol dst) {
  }

  /** Whether any file has been indexed for this profile. */
  boolean isCodeIndexPresent(String profileId);

  /** Aggregate counts for the status surface. */
  CodeIndexCounts counts(String profileId);

  /** Code-index size for {@code GET /code/status}. */
  record CodeIndexCounts(long files, long symbols, long resolvedEdges, long heuristicEdges) {
    public long edges() {
      return resolvedEdges + heuristicEdges;
    }
  }
}
