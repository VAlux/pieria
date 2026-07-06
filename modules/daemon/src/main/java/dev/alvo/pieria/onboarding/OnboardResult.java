package dev.alvo.pieria.onboarding;

/**
 * Outcome of ingesting one onboarding source, uniform across source kinds so the client can render
 * a single "done" line regardless of which source ran. {@code documents} counts the units the
 * source discovered (markdown/web docs, or source files received); {@code memoriesStored} is how
 * many memories the run wrote.
 *
 * <p>The code-index fields ({@code symbols}, {@code edges}, {@code summariesStored}) are populated
 * only by the source-code source and are {@code null} for content sources — the client shows the
 * extra code detail only when present.
 */
public record OnboardResult(
  String sourceType,
  int documents,
  int memoriesStored,
  Integer symbols,
  Integer edges,
  Integer summariesStored) {

  /** Result of a content source (markdown, web) that feeds the memory-extraction pipeline. */
  public static OnboardResult content(String sourceType, int documents, int memoriesStored) {
    return new OnboardResult(sourceType, documents, memoriesStored, null, null, null);
  }

  /** Result of the source-code source, carrying code-index-specific counts. */
  public static OnboardResult code(int filesReceived, int memoriesStored,
                                   int symbols, int edges, int summariesStored) {
    return new OnboardResult("source-code", filesReceived, memoriesStored, symbols, edges, summariesStored);
  }
}
