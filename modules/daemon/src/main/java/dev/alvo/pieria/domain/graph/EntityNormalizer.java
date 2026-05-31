package dev.alvo.pieria.domain.graph;

import java.util.Locale;
import java.util.Map;

/**
 * Deterministic, Java-side normalization of entity names, types, and relation labels. Normalization
 * runs before content-addressed id computation so that surface variants ("Redis", " redis ",
 * "REDIS") collapse to a single node. Aliasing is intentionally minimal here; richer node merging is
 * owned by a later consolidation phase.
 *
 * <p>The normalized form is also what is persisted in the {@code entities.name}/{@code type} and
 * {@code edges.relation} columns, so that the unique {@code (profile_id, type, name)} index dedupes
 * variants.
 */
public final class EntityNormalizer {

  private EntityNormalizer() {
  }

  /** A few high-frequency aliases collapsed to a canonical form. Extend deliberately. */
  private static final Map<String, String> NAME_ALIASES = Map.of(
    "postgres", "postgresql",
    "pg", "postgresql",
    "js", "javascript",
    "ts", "typescript");

  /**
   * Normalize an entity name: trim, collapse internal whitespace, lowercase, then apply the alias
   * map. Returns {@code ""} for null/blank input (callers should drop empty names).
   */
  public static String normalizeName(String raw) {
    String base = collapse(raw);
    if (base.isEmpty()) {
      return "";
    }
    return NAME_ALIASES.getOrDefault(base, base);
  }

  /**
   * Normalize an entity type to a lowercase token. Falls back to {@code "concept"} when null/blank
   * so every node has a stable type for id computation.
   */
  public static String normalizeType(String raw) {
    String base = collapse(raw);
    return base.isEmpty() ? "concept" : base;
  }

  /**
   * Normalize a relation label: trim, collapse whitespace, lowercase. Returns {@code ""} for
   * null/blank input (callers should drop empty relations).
   */
  public static String normalizeRelation(String raw) {
    return collapse(raw);
  }

  private static String collapse(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }
}
