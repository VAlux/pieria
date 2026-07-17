package dev.alvo.pieria.onboarding;

/**
 * Outcome of ingesting one onboarding source, uniform across source kinds so the client can render
 * a single "done" line regardless of which source ran. {@code documents} counts the units the
 * source discovered (markdown/web docs, or source files received); {@code memoriesStored} is how
 * many memories the run wrote.
 *
 * <p>{@code documentsSkipped} is populated by content sources: how many of the discovered
 * documents were unchanged since the last onboard (ledger hash match) and skipped the model
 * pipeline. {@code null} for the source-code source, which has its own unchanged-skip counters.
 *
 * <p>The code-index fields ({@code symbols}, {@code edges}, {@code summariesStored}) are populated
 * only by the source-code source and are {@code null} for content sources — the client shows the
 * extra code detail only when present.
 */
public record OnboardResult(
  String sourceType,
  int documents,
  int memoriesStored,
  int graphDeferred,
  Integer documentsSkipped,
  Integer symbols,
  Integer edges,
  Integer summariesStored) {

  /** Result of a content source (markdown, text, pdf, web) that feeds the memory-extraction pipeline. */
  public static OnboardResult content(String sourceType, int documents, int memoriesStored, int documentsSkipped) {
    return content(sourceType, documents, memoriesStored, documentsSkipped, memoriesStored);
  }

  public static OnboardResult content(String sourceType, int documents, int memoriesStored,
                                      int documentsSkipped, int graphDeferred) {
    return new OnboardResult(sourceType, documents, memoriesStored, graphDeferred,
      documentsSkipped, null, null, null);
  }

  /** Result of the source-code source, carrying code-index-specific counts. */
  public static OnboardResult code(int filesReceived, int memoriesStored,
                                   int symbols, int edges, int summariesStored) {
    return new OnboardResult("source-code", filesReceived, memoriesStored, 0,
      null, symbols, edges, summariesStored);
  }
}
