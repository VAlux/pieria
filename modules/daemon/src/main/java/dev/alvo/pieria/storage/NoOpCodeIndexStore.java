package dev.alvo.pieria.storage;

import dev.alvo.pieria.domain.code.CodeEdge;
import dev.alvo.pieria.domain.code.CodeFile;
import dev.alvo.pieria.domain.code.CodeModule;
import dev.alvo.pieria.domain.code.CodeSymbol;
import dev.alvo.pieria.domain.code.EdgeConfidence;

import java.util.List;
import java.util.Optional;

/**
 * A {@link CodeIndexStore} with no code index: every read is empty, every write a no-op. Used where
 * source-code indexing is not wired (the evaluation harness, retrieval unit tests) so the code
 * retrieval channels degrade cleanly to "no results" rather than requiring the SQLite substrate.
 */
public class NoOpCodeIndexStore implements CodeIndexStore {

  @Override
  public CodeModule upsertCodeModule(String profileId, CodeModule module) {
    return module;
  }

  @Override
  public CodeFile upsertCodeFile(String profileId, CodeFile file) {
    return file;
  }

  @Override
  public CodeSymbol upsertCodeSymbol(String profileId, CodeSymbol symbol) {
    return symbol;
  }

  @Override
  public CodeEdge upsertCodeEdge(String profileId, CodeEdge edge) {
    return edge;
  }

  @Override
  public Optional<String> fileContentHash(String profileId, String repoRelPath) {
    return Optional.empty();
  }

  @Override
  public void replaceFileIndex(String profileId, CodeFile file, List<CodeSymbol> symbols, List<CodeEdge> edges) {
    // no-op
  }

  @Override
  public List<CodeSymbol> searchSymbolsFts(String profileId, String matchQuery, int limit) {
    return List.of();
  }

  @Override
  public List<CodeSymbol> findSymbolsByName(String profileId, List<String> names, int limit) {
    return List.of();
  }

  @Override
  public List<CodeSymbol> findSymbolsByQualifiedName(String profileId, List<String> qualifiedNames, int limit) {
    return List.of();
  }

  @Override
  public List<CodeSymbol> findSymbolsByIds(String profileId, List<String> ids, int limit) {
    return List.of();
  }

  @Override
  public List<String> symbolNeighborhood(
    String profileId, List<String> seedSymbolIds, int depth, int fanout, EdgeConfidence minConfidence) {
    return List.of();
  }

  @Override
  public List<EdgeEvidence> findEdgesTouching(
    String profileId, List<String> symbolIds, EdgeConfidence minConfidence, int limit) {
    return List.of();
  }

  @Override
  public boolean isCodeIndexPresent(String profileId) {
    return false;
  }

  @Override
  public CodeIndexCounts counts(String profileId) {
    return new CodeIndexCounts(0, 0, 0, 0);
  }
}
