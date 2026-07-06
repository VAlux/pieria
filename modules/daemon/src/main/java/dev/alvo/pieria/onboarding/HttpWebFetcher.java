package dev.alvo.pieria.onboarding;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Production {@link WebFetcher}: GETs the page over HTTP(S) and extracts its main text with Jsoup.
 * Boilerplate elements (script/style/nav/header/footer/aside) are stripped before the body text is
 * read, so what reaches the extractor is closer to the article content than the raw DOM.
 */
@Component
public class HttpWebFetcher implements WebFetcher {

  private final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build();

  @Override
  public FetchedPage fetch(String url) {
    URI uri = URI.create(url);
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new IllegalArgumentException("web source url must be http(s): " + url);
    }

    HttpRequest request = HttpRequest.newBuilder(uri)
      .timeout(Duration.ofSeconds(30))
      .header("User-Agent", "Pieria-Onboard/1.0")
      .header("Accept", "text/html,application/xhtml+xml")
      .GET()
      .build();

    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new WebFetchException("failed to fetch " + url + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WebFetchException("interrupted while fetching " + url, e);
    }

    if (response.statusCode() / 100 != 2) {
      throw new WebFetchException("failed to fetch " + url + ": HTTP " + response.statusCode());
    }

    Document document = Jsoup.parse(response.body(), url);
    document.select("script, style, noscript, nav, header, footer, aside, form").remove();
    String title = document.title() == null ? "" : document.title().strip();
    String text = document.body() == null ? "" : document.body().text();
    return new FetchedPage(title, text);
  }

  /** A page fetch failed (transport error or non-2xx); fails the onboard task with a clear message. */
  static final class WebFetchException extends RuntimeException {
    WebFetchException(String message) {
      super(message);
    }

    WebFetchException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
