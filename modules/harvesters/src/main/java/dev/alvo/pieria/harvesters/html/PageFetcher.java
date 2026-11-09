package dev.alvo.pieria.harvesters.html;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Fetches one URL at a time, spacing requests to each host and checking every hop of a redirect. */
public final class PageFetcher {

  static final String USER_AGENT = "PieriaHarvester/1.0";
  private static final int MAX_REDIRECTS = 5;
  private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;
  private static final long DEFAULT_RETRY_SECONDS = 300;
  private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
  private static final Set<String> HTML_TYPES = Set.of("text/html", "application/xhtml+xml");

  private final HttpClient client = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .followRedirects(HttpClient.Redirect.NEVER)
    .connectTimeout(Duration.ofSeconds(10))
    .build();
  private final Consumer<String> hostCheck;
  private final long delayMillis;
  private final Duration requestDeadline;
  private final Map<String, Long> hostDelays = new HashMap<>();
  private final Map<String, Long> lastRequestNanos = new HashMap<>();

  /** @param hostCheck throws {@link BlockedAddressException} for a host that must not be contacted */
  public PageFetcher(Consumer<String> hostCheck, long delayMillis, Duration requestDeadline) {
    this.hostCheck = hostCheck;
    this.delayMillis = delayMillis;
    this.requestDeadline = requestDeadline;
  }

  /** Fetches an HTML page; any other content type is {@link FetchResult.Skipped}. */
  public FetchResult fetch(String url, String etag, String lastModified) {
    return exchange(url, etag, lastModified, true);
  }

  public FetchResult fetchAny(String url) {
    return exchange(url, null, null, false);
  }

  /** A longer minimum gap for one host, such as from its {@code Crawl-delay}. */
  public void setHostDelay(String host, long millis) {
    hostDelays.put(host, millis);
  }

  private FetchResult exchange(String url, String etag, String lastModified, boolean htmlOnly) {
    String current = url;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      URI uri = URI.create(current);
      try {
        hostCheck.accept(uri.getHost());
      } catch (BlockedAddressException e) {
        return new FetchResult.Blocked(e.getMessage());
      }
      if (!pause(uri.getHost())) {
        return new FetchResult.Failed("interrupted");
      }
      HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET()
        .header("User-Agent", USER_AGENT)
        .header("Accept", htmlOnly ? "text/html,application/xhtml+xml" : "*/*");
      if (hop == 0 && etag != null) {
        request.header("If-None-Match", etag);
      }
      if (hop == 0 && lastModified != null) {
        request.header("If-Modified-Since", lastModified);
      }
      CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request.build(), _ -> new CappedBody(MAX_BODY_BYTES));
      HttpResponse<byte[]> response;
      try {
        response = pending.get(requestDeadline.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        pending.cancel(true);
        return new FetchResult.Failed("timed out: " + current);
      } catch (InterruptedException e) {
        pending.cancel(true);
        Thread.currentThread().interrupt();
        return new FetchResult.Failed("interrupted");
      } catch (ExecutionException e) {
        return tooLarge(e) ? new FetchResult.Skipped("larger than " + MAX_BODY_BYTES + " bytes") : new FetchResult.Failed(reason(e));
      }
      if (!REDIRECTS.contains(response.statusCode())) {
        return classify(current, response, htmlOnly);
      }
      String location = response.headers().firstValue("Location").orElse(null);
      if (location == null) {
        return new FetchResult.Failed("redirect with no location: " + current);
      }
      String next = resolve(uri, location);
      if (next == null) {
        return new FetchResult.Skipped("redirect to an unsupported URL: " + current);
      }
      current = next;
    }
    return new FetchResult.Skipped("too many redirects: " + url);
  }

  private static String resolve(URI base, String location) {
    try {
      return CrawlScope.normalize(base.resolve(location.strip().replace(" ", "%20")).toString());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private FetchResult classify(String url, HttpResponse<byte[]> response, boolean htmlOnly) {
    int status = response.statusCode();
    String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
    if (status / 100 == 2) {
      String type = response.headers().firstValue("Content-Type").orElse(null);
      if (htmlOnly && type != null && !HTML_TYPES.contains(type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT))) {
        return new FetchResult.Skipped("not HTML: " + url);
      }
      return new FetchResult.Page(url, response.body(), type, response.headers().firstValue("ETag").orElse(null),
        response.headers().firstValue("Last-Modified").orElse(null), response.headers().firstValue("X-Robots-Tag").orElse(null));
    }
    return switch (status) {
      case 304 -> new FetchResult.NotModified();
      case 404, 410 -> new FetchResult.Gone();
      case 401, 403 -> new FetchResult.Denied();
      case 429 -> {
        Long seconds = retryAfterSeconds(retryAfter);
        yield new FetchResult.RateLimited(seconds == null ? DEFAULT_RETRY_SECONDS : seconds);
      }
      case 503 -> {
        Long seconds = retryAfterSeconds(retryAfter);
        yield seconds == null ? new FetchResult.Failed("HTTP 503: " + url) : new FetchResult.RateLimited(seconds);
      }
      default -> new FetchResult.Failed("HTTP " + status + ": " + url);
    };
  }

  /** Seconds to wait, or null when the header is absent or unreadable. */
  private static Long retryAfterSeconds(String header) {
    if (header == null) {
      return null;
    }
    try {
      return Math.max(0, Long.parseLong(header.strip()));
    } catch (NumberFormatException notSeconds) {
      try {
        Instant when = ZonedDateTime.parse(header.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        return Math.max(0, Duration.between(Instant.now(), when).toSeconds());
      } catch (DateTimeParseException notADate) {
        return null;
      }
    }
  }

  private boolean pause(String host) {
    long interval = Math.max(delayMillis, hostDelays.getOrDefault(host, 0L));
    Long last = lastRequestNanos.get(host);
    if (last != null) {
      long wait = interval - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - last);
      if (wait > 0) {
        try {
          Thread.sleep(wait);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    lastRequestNanos.put(host, System.nanoTime());
    return true;
  }

  private static boolean tooLarge(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof BodyTooLargeException) {
        return true;
      }
    }
    return false;
  }

  private static String reason(ExecutionException failure) {
    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }

  private static final class BodyTooLargeException extends IOException {
  }

  /** Collects a response body and gives up past a size, so a huge page can't exhaust memory. */
  private static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {

    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final int maxBytes;
    private Flow.Subscription subscription;

    CappedBody(int maxBytes) {
      this.maxBytes = maxBytes;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
      if (result.isDone()) {
        return;
      }
      for (ByteBuffer item : items) {
        byte[] chunk = new byte[item.remaining()];
        item.get(chunk);
        bytes.writeBytes(chunk);
      }
      if (bytes.size() > maxBytes) {
        subscription.cancel();
        result.completeExceptionally(new BodyTooLargeException());
      }
    }

    @Override
    public void onError(Throwable throwable) {
      result.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
