package dev.alvo.pieria.harvesters.html;

/** How one request ended. */
public sealed interface FetchResult {

  record Page(String finalUrl, byte[] body, String contentType, String etag, String lastModified, String robotsHeader) implements FetchResult {
  }

  record NotModified() implements FetchResult {
  }

  /** The server says the page doesn't exist. */
  record Gone() implements FetchResult {
  }

  /** The server refused access, which says nothing about whether the page exists. */
  record Denied() implements FetchResult {
  }

  /** The URL is not something the crawler handles, such as a PDF or an oversized page. */
  record Skipped(String reason) implements FetchResult {
  }

  record Failed(String reason) implements FetchResult {
  }

  record Blocked(String reason) implements FetchResult {
  }

  record RateLimited(long retryAfterSeconds) implements FetchResult {
  }
}
