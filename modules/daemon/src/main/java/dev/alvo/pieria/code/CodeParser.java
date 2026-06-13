package dev.alvo.pieria.code;

import dev.alvo.pieria.domain.code.CodeRelation;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.domain.code.EdgeConfidence;

import java.util.List;

/**
 * The parsing seam: turns one source file's text into a deterministic, id-free structural result
 * (symbols + edges referenced by qualified name). This is the boundary that decouples the whole
 * Phase 13 pipeline from Tree-sitter — the real {@code TreeSitterCodeParser} is one implementation;
 * tests use a fake; production runs with no parser at all degrade to file/module/dependency facts.
 *
 * <p>Ids and {@code fileId} are assigned downstream by {@code CodeIndexingService} (which knows the
 * stored file id), so the parser never computes content-addressed ids itself.
 */
public interface CodeParser {

  /** Whether this parser handles the given language-pack id (e.g. {@code "java"}). */
  boolean supports(String language);

  /** Parse one file; never throws for ordinary syntax problems — returns what it could extract. */
  ParseResult parse(ParseInput input);

  /** One file to parse. */
  record ParseInput(String repoRelPath, String language, String content) {
  }

  /** The id-free structural output for one file. */
  record ParseResult(List<ParsedSymbol> symbols, List<ParsedEdge> edges) {

    public ParseResult {
      symbols = symbols == null ? List.of() : List.copyOf(symbols);
      edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static ParseResult empty() {
      return new ParseResult(List.of(), List.of());
    }
  }

  /**
   * A declaration. {@code qualifiedName} must be unique within the file so edges can reference it;
   * {@code parentQualifiedName} links a member to its enclosing symbol (or null at top level).
   */
  record ParsedSymbol(
    CodeSymbolKind kind,
    String name,
    String qualifiedName,
    String signature,
    String visibility,
    int startLine,
    int endLine,
    String parentQualifiedName) {
  }

  /**
   * A relation from a source symbol (by qualified name) to a target. {@code dstQualifiedName} is set
   * when the parser believes the target resolves to a concrete declaration (used to resolve to a
   * symbol id within this file or globally); {@code dstRef} is the bare target name and is always
   * set (the identity component for unresolved cross-file targets).
   */
  record ParsedEdge(
    String srcQualifiedName,
    CodeRelation relation,
    EdgeConfidence confidence,
    String dstQualifiedName,
    String dstRef) {
  }
}
