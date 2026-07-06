package dev.alvo.pieria.onboarding;

/**
 * Fetches a web page and extracts its readable main text. An injected seam so the web onboarding
 * source is testable without network access; {@link HttpWebFetcher} is the production wiring.
 */
public interface WebFetcher {

  /**
   * Fetch {@code url} and return its extracted text. Throws on any transport / non-2xx failure; the
   * caller decides whether to skip the page or fail the run.
   */
  FetchedPage fetch(String url);

  /** A fetched page: its {@code <title>} (may be blank) and extracted main text. */
  record FetchedPage(String title, String text) {
  }
}
