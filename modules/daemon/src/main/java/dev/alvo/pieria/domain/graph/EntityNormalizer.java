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

  /**
   * A few high-frequency aliases collapsed to a canonical form. Extend deliberately.
   */
  private static final Map<String, String> NAME_ALIASES = Map.of(
    "postgres", "postgresql",
    "pg", "postgresql",
    "js", "javascript",
    "ts", "typescript");
  /**
   * Verb-form variants collapsed to the dominant stored form. The model returns the same relation in
   * both base and third-person form across calls, which splits one relation into two nodes' worth of
   * edges — the live graph carries {@code includes} (1,568 edges) alongside {@code include} (1,373).
   * Only the observed high-frequency pairs are listed; unknown relations still pass through
   * normalized rather than being dropped, so nothing is lost. Extend deliberately.
   */
  private static final Map<String, String> RELATION_ALIASES = Map.ofEntries(
    Map.entry("include", "includes"),
    Map.entry("use", "uses"),
    Map.entry("contain", "contains"),
    Map.entry("require", "requires"),
    Map.entry("return", "returns"),
    Map.entry("run", "runs"),
    Map.entry("define", "defines"),
    Map.entry("own", "owns"),
    Map.entry("provide", "provides"),
    Map.entry("support", "supports"),
    Map.entry("extend", "extends"),
    Map.entry("implement", "implements"),
    Map.entry("depend on", "depends on"),
    Map.entry("depends_on", "depends on"));

  private EntityNormalizer() {
  }

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
   * Normalize a relation label: trim, collapse whitespace, lowercase, then apply the alias map.
   * Returns {@code ""} for null/blank input (callers should drop empty relations).
   */
  public static String normalizeRelation(String raw) {
    String base = collapse(raw);
    if (base.isEmpty()) {
      return "";
    }
    return RELATION_ALIASES.getOrDefault(base, base);
  }

  private static String collapse(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }
}
