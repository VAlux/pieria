package dev.alvo.pieria.domain.code;

import dev.alvo.pieria.domain.ContentId;

/**
 * A declaration extracted from a source file: a class, method, field, endpoint, config key, etc.
 * The {@code id} is content-addressed over {@code (profileId, fileId, kind, qualifiedName,
 * signature)} (see {@link ContentId#forCodeSymbol}) so an unchanged declaration keeps its id across
 * re-index while a changed signature yields a new one, and overloads (same name, different
 * signature) stay distinct rows.
 *
 * <p>{@code path} is the owning file's repo-relative path, denormalized here so the external-content
 * {@code code_symbols_fts} index can match on it. {@code id} is assigned at store time when null.
 *
 * @param qualifiedName  best-effort fully-qualified name (e.g. {@code com.x.Bar#create})
 * @param signature      declaration signature, or null
 * @param visibility     {@code public}/{@code private}/… or null
 * @param parentSymbolId enclosing symbol id (e.g. the class of a method), or null
 */
public record CodeSymbol(
  String id,
  String profileId,
  String fileId,
  CodeSymbolKind kind,
  String name,
  String qualifiedName,
  String signature,
  String visibility,
  int startLine,
  int endLine,
  String language,
  String parentSymbolId,
  String path) {

  /**
   * A freshly extracted symbol with no id yet (assigned at store time).
   */
  public static CodeSymbol of(CodeSymbolKind kind,
                              String name,
                              String qualifiedName,
                              String signature,
                              String visibility,
                              int startLine,
                              int endLine,
                              String language,
                              String parentSymbolId) {
    return new CodeSymbol(null, null, null, kind, name, qualifiedName, signature, visibility,
      startLine, endLine, language, parentSymbolId, null);
  }
}
