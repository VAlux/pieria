package dev.alvo.pieria.harvesters.html;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class PageFetcherTests {

  private final StubSite site = StubSite.start();

  @AfterEach
  void tearDown() {
    site.close();
  }

  private static PageFetcher fetcher(Consumer<String> hostCheck, long delayMillis) {
    return new PageFetcher(hostCheck, delayMillis, Duration.ofSeconds(10));
  }

  private static PageFetcher fetcher() {
    return fetcher(_ -> { }, 0);
  }

  private String url(String path) {
    return site.baseUrl() + path;
  }

  @Test
  void returnsAPageWithItsValidators() {
    site.respond("/a", 200, Map.of("Content-Type", "text/html", "ETag", "\"v1\"", "Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT",
      "X-Robots-Tag", "noindex"), "<p>hi</p>");

    FetchResult.Page page = (FetchResult.Page) fetcher().fetch(url("/a"), null, null);

    assertThat(new String(page.body())).isEqualTo("<p>hi</p>");
    assertThat(page.finalUrl()).isEqualTo(url("/a"));
    assertThat(page.etag()).isEqualTo("\"v1\"");
    assertThat(page.lastModified()).isEqualTo("Wed, 21 Oct 2026 07:28:00 GMT");
    assertThat(page.robotsHeader()).isEqualTo("noindex");
    assertThat(site.lastHeader("/a", "User-Agent")).isEqualTo("PieriaHarvester/1.0");
  }

  @Test
  void sendsValidatorsAndReportsNotModified() {
    site.respond("/a", 304, Map.of(), "");

    assertThat(fetcher().fetch(url("/a"), "\"v1\"", "yesterday")).isInstanceOf(FetchResult.NotModified.class);

    assertThat(site.lastHeader("/a", "If-None-Match")).isEqualTo("\"v1\"");
    assertThat(site.lastHeader("/a", "If-Modified-Since")).isEqualTo("yesterday");
  }

  @Test
  void followsARedirectAndReportsTheFinalUrl() {
    site.respond("/old", 301, Map.of("Location", "/new"), "");
    site.page("/new", "<p>x</p>");

    FetchResult.Page page = (FetchResult.Page) fetcher().fetch(url("/old"), "\"v1\"", null);

    assertThat(page.finalUrl()).isEqualTo(url("/new"));
    assertThat(site.lastHeader("/new", "If-None-Match")).isNull();
  }

  @Test
  void aRedirectToABlockedHostIsNotFollowed() {
    site.respond("/a", 302, Map.of("Location", "http://127.0.0.2:" + site.port() + "/x"), "");
    Consumer<String> guard = host -> {
      if (host.equals("127.0.0.2")) {
        throw new BlockedAddressException("blocked");
      }
    };

    assertThat(fetcher(guard, 0).fetch(url("/a"), null, null)).isInstanceOf(FetchResult.Blocked.class);

    assertThat(site.hits("/x")).isZero();
  }

  @Test
  void aBlockedHostIsNeverRequested() {
    Consumer<String> guard = _ -> {
      throw new BlockedAddressException("blocked");
    };

    assertThat(fetcher(guard, 0).fetch(url("/a"), null, null)).isInstanceOf(FetchResult.Blocked.class);

    assertThat(site.hits("/a")).isZero();
  }

  @Test
  void stopsAfterTooManyRedirects() {
    site.respond("/a", 302, Map.of("Location", "/a"), "");

    assertThat(fetcher().fetch(url("/a"), null, null)).isInstanceOf(FetchResult.Skipped.class);
  }

  @Test
  void aRedirectWithNoLocationFails() {
    site.respond("/a", 302, Map.of(), "");

    assertThat(fetcher().fetch(url("/a"), null, null)).isInstanceOf(FetchResult.Failed.class);
  }

  @ParameterizedTest
  @CsvSource({"404,Gone", "410,Gone", "401,Denied", "403,Denied", "500,Failed", "503,Failed", "400,Failed"})
  void mapsStatus(int status, String type) {
    site.respond("/a", status, Map.of(), "x");

    assertThat(fetcher().fetch(url("/a"), null, null).getClass().getSimpleName()).isEqualTo(type);
  }

  @Test
  void rateLimitReadsRetryAfterSeconds() {
    site.respond("/a", 429, Map.of("Retry-After", "120"), "");

    assertThat(fetcher().fetch(url("/a"), null, null)).isEqualTo(new FetchResult.RateLimited(120));
  }

  @Test
  void rateLimitReadsARetryAfterDate() {
    String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().plusSeconds(100).atZone(ZoneOffset.UTC));
    site.respond("/a", 503, Map.of("Retry-After", date), "");

    FetchResult.RateLimited limited = (FetchResult.RateLimited) fetcher().fetch(url("/a"), null, null);

    assertThat(limited.retryAfterSeconds()).isBetween(90L, 100L);
  }

  @Test
  void rateLimitWithoutAHeaderDefaults() {
    site.respond("/a", 429, Map.of(), "");

    assertThat(fetcher().fetch(url("/a"), null, null)).isEqualTo(new FetchResult.RateLimited(300));
  }

  @Test
  void skipsNonHtml() {
    site.respond("/a", 200, Map.of("Content-Type", "application/pdf"), "x");

    assertThat(fetcher().fetch(url("/a"), null, null)).isInstanceOf(FetchResult.Skipped.class);
  }

  @Test
  void fetchAnyAcceptsOtherTypes() {
    site.respond("/robots.txt", 200, Map.of("Content-Type", "text/plain"), "x");

    assertThat(fetcher().fetchAny(url("/robots.txt"))).isInstanceOf(FetchResult.Page.class);
  }

  @Test
  void anAbsentContentTypeCountsAsHtml() {
    site.respond("/a", 200, Map.of(), "x");

    assertThat(fetcher().fetch(url("/a"), null, null)).isInstanceOf(FetchResult.Page.class);
  }

  @Test
  void skipsAnOversizedBody() {
    site.respond("/a", 200, Map.of("Content-Type", "text/html"), new byte[11 * 1024 * 1024]);

    assertThat(fetcher().fetch(url("/a"), null, null)).isInstanceOf(FetchResult.Skipped.class);
  }

  @Test
  void waitsBetweenRequestsToAHost() {
    site.page("/a", "x");
    PageFetcher fetcher = fetcher(_ -> { }, 200);
    long start = System.nanoTime();

    fetcher.fetch(url("/a"), null, null);
    fetcher.fetch(url("/a"), null, null);

    assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
  }

  @Test
  void aHostDelayExtendsTheWait() {
    site.page("/a", "x");
    PageFetcher fetcher = fetcher(_ -> { }, 0);
    fetcher.setHostDelay("127.0.0.1", 200);
    long start = System.nanoTime();

    fetcher.fetch(url("/a"), null, null);
    fetcher.fetch(url("/a"), null, null);

    assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
  }

  @Test
  void aStalledBodyHitsTheDeadline() {
    site.stall("/a");
    long start = System.nanoTime();

    assertThat(new PageFetcher(_ -> { }, 0, Duration.ofMillis(500)).fetch(url("/a"), null, null)).isInstanceOf(FetchResult.Failed.class);

    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void aRefusedConnectionFails() {
    int port = site.port();
    site.close();

    assertThat(fetcher().fetch("http://127.0.0.1:" + port + "/a", null, null)).isInstanceOf(FetchResult.Failed.class);
  }
}
