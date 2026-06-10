package dev.alvo.pieria.domain.code;

import dev.alvo.pieria.domain.ContentId;

import java.time.Instant;

/**
 * One indexed source file. The {@code id} is content-addressed over {@code (profileId, repoRelPath)}
 * — <em>path-stable</em>, not content-versioned (see {@link ContentId#forCodeFile}) — so a file
 * keeps one stable id across edits and {@link CodeSymbol}/{@link CodeEdge} rows can foreign-key it
 * while {@code replaceFileIndex} re-indexes its contents in place. The actual content version lives
 * in {@code contentHash}: an unchanged hash means the file can be skipped (idempotent re-index), and
 * derived memories are content-addressed by this hash so unchanged code regenerates nothing.
 *
 * <p>{@code id} and {@code indexedAt} are assigned at store time when null.
 *
 * @param language    language pack id (e.g. {@code "java"}); the empty/unknown pack still yields a
 *                    {@code CodeFile} row with no symbols
 * @param repoRelPath repo-relative path (the identity component)
 * @param contentHash hash of the file's content (the version component)
 * @param loc         lines of code
 * @param moduleId    owning {@link CodeModule} id, or null
 */
public record CodeFile(
  String id,
  String profileId,
  String language,
  String repoRelPath,
  String contentHash,
  int loc,
  String moduleId,
  Instant indexedAt) {

  /** A freshly discovered file with no id or timestamp yet (assigned at store time). */
  public static CodeFile of(String language, String repoRelPath, String contentHash, int loc, String moduleId) {
    return new CodeFile(null, null, language, repoRelPath, contentHash, loc, moduleId, null);
  }
}
