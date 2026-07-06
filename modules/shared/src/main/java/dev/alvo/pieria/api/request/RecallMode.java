package dev.alvo.pieria.api.request;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * The inference tier for a single recall, ordered cheapest → richest. Each tier is a superset of the
 * one above it, letting a caller (or an agent) trade latency and inference cost against answer
 * richness:
 *
 * <ul>
 *   <li>{@link #EVIDENCE} — deterministic query analysis (no {@code analyzeQuery} model call),
 *       embed-only vector channels, and no synthesis. Returns the fused memories with a {@code null}
 *       answer in ~1-3s. This is the tier the auto-recall injection hooks use.</li>
 *   <li>{@link #ANALYZED} — model-driven query analysis + HyDE for better channel targeting, but
 *       still no synthesis ({@code null} answer). A middle tier: sharper retrieval without paying for
 *       the large synthesis model.</li>
 *   <li>{@link #SYNTHESIZED} — the full pipeline: model analysis, deterministic temporal facts, and
 *       large-model synthesis of a written answer. The default, and what the {@code recall} MCP tool
 *       promises when it returns an answer.</li>
 * </ul>
 *
 * <p>Wire form is the (case-insensitive) constant name; blank binds to {@code null} so an absent
 * request field defers to the profile's configured default.
 */
public enum RecallMode {
  EVIDENCE,
  ANALYZED,
  SYNTHESIZED;

  /** Whether this tier runs the model-driven query analysis (and thus HyDE) rather than the deterministic fallback. */
  public boolean usesModelAnalysis() {
    return this != EVIDENCE;
  }

  /** Whether this tier computes temporal facts and synthesizes a written answer. */
  public boolean synthesizes() {
    return this == SYNTHESIZED;
  }

  /**
   * Parse a wire value case-insensitively; blank ⇒ {@code null} (defer to the configured default).
   * An unrecognized non-blank value throws {@link IllegalArgumentException} (mapped to 400 on the
   * REST surface).
   */
  @JsonCreator
  public static RecallMode fromWire(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return RecallMode.valueOf(value.strip().toUpperCase(Locale.ROOT));
  }
}
