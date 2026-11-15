package dev.alvo.pieria.harvesters.http;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Sends requests to a JSON API: redirects are never followed, bodies are capped, a short
 * {@code Retry-After} is waited out in process, and the server's clock is kept from its {@code Date}
 * header. {@code label} names the service in every message, so it must not hold a secret.
 */
public final class JsonTransport {

  public static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
  public static final int MAX_RETRIES = 3;
  public static final Duration MAX_INLINE_WAIT = Duration.ofSeconds(30);
  public static final String USER_AGENT = "PieriaHarvester/1.0";
  private static final long DEFAULT_RETRY_SECONDS = 5;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A response with its whole body read. */
  public record Response(int status, HttpHeaders headers, byte[] body) {
  }

  private final String label;
  private final Duration requestDeadline;
  private final HttpClient http;
  private volatile Instant serverTime;

  public JsonTransport(String label, Duration requestDeadline) {
    this.label = label;
    this.requestDeadline = requestDeadline;
    this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(30)).build();
  }

  /** A request to {@code uri} with the deadline, {@code Accept} and {@code User-Agent} set. */
  public HttpRequest.Builder request(URI uri) {
    return HttpRequest.newBuilder(uri).timeout(requestDeadline).header("Accept", "application/json").header("User-Agent", USER_AGENT);
  }

  /** Encodes a query or form value; spaces become {@code %20}. */
  public static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** The server's clock, from the latest response's {@code Date} header, else the local clock. */
  public Instant serverTime() {
    Instant time = serverTime;
    return time == null ? Instant.now() : time;
  }

  /**
   * Sends {@code request}, retrying a {@code 429} or {@code 503} whose {@code Retry-After} is short.
   * Any other status is returned for the caller to interpret.
   */
  public Response send(HttpRequest request) throws HarvesterException {
    for (int attempt = 0; ; attempt++) {
      HttpResponse<InputStream> response = exchange(request);
      byte[] body = read(response);
      response.headers().firstValue("Date").map(JsonTransport::httpDate).ifPresent(time -> serverTime = time);
      int status = response.statusCode();
      if (status == 429 || status == 503) {
        long wait = retryAfter(response.headers().firstValue("Retry-After").orElse(null));
        if (attempt < MAX_RETRIES && wait <= MAX_INLINE_WAIT.toSeconds()) {
          pause(wait);
          continue;
        }
        throw new HarvesterException(ErrorClass.RATE_LIMITED, label + " is limiting requests (HTTP " + status + ")", wait);
      }
      return new Response(status, response.headers(), body);
    }
  }

  /** The body as a JSON object or array; anything else means the service is misbehaving. */
  public static JsonNode json(byte[] body, String label) throws HarvesterException {
    try {
      JsonNode node = MAPPER.readTree(body);
      if (node == null || node.isMissingNode() || !(node.isObject() || node.isArray())) {
        throw new HarvesterException(ErrorClass.TRANSIENT, label + " answered with something that is not JSON");
      }
      return node;
    } catch (JacksonException e) {
      throw new HarvesterException(ErrorClass.TRANSIENT, label + " answered with something that is not JSON");
    }
  }

  private HttpResponse<InputStream> exchange(HttpRequest request) throws HarvesterException {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException e) {
      throw new HarvesterException(ErrorClass.TRANSIENT, "could not reach " + label + ": " + e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HarvesterException(ErrorClass.TRANSIENT, "interrupted while reading " + label);
    }
  }

  private byte[] read(HttpResponse<InputStream> response) throws HarvesterException {
    try (InputStream in = response.body()) {
      byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
      if (body.length > MAX_BODY_BYTES) {
        throw new HarvesterException(ErrorClass.TRANSIENT, label + " sent a response over " + MAX_BODY_BYTES + " bytes");
      }
      return body;
    } catch (IOException e) {
      throw new HarvesterException(ErrorClass.TRANSIENT, "could not read from " + label + ": " + e.getClass().getSimpleName());
    }
  }

  private static void pause(long seconds) throws HarvesterException {
    try {
      Thread.sleep(Duration.ofSeconds(seconds));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HarvesterException(ErrorClass.TRANSIENT, "interrupted while waiting to retry");
    }
  }

  private static long retryAfter(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT_RETRY_SECONDS;
    }
    try {
      return Math.max(0, Long.parseLong(value.strip()));
    } catch (NumberFormatException e) {
      Instant at = httpDate(value);
      return at == null ? DEFAULT_RETRY_SECONDS : Math.max(0, Duration.between(Instant.now(), at).toSeconds());
    }
  }

  private static Instant httpDate(String value) {
    try {
      return ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
