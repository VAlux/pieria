package dev.alvo.pieria.harvesters.atlassian;

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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads JSON from one Atlassian site. Requests only ever go to the site's own address, and redirects
 * are never followed, so the credentials can't reach another host.
 */
public final class AtlassianClient {

  public static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
  public static final int MAX_RETRIES = 3;
  public static final Duration MAX_INLINE_WAIT = Duration.ofSeconds(30);
  private static final long DEFAULT_RETRY_SECONDS = 5;
  private static final String USER_AGENT = "PieriaHarvester/1.0";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final AtlassianSite site;
  private final Duration requestDeadline;
  private final HttpClient http;
  private volatile Instant serverTime;

  public AtlassianClient(AtlassianSite site, Duration requestDeadline) {
    this.site = site;
    this.requestDeadline = requestDeadline;
    this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(30)).build();
  }

  public AtlassianSite site() {
    return site;
  }

  /** Encodes a query value; spaces become {@code %20}. */
  public static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** The site's clock, from the latest response's {@code Date} header, else the local clock. */
  public Instant serverTime() {
    Instant time = serverTime;
    return time == null ? Instant.now() : time;
  }

  /** The JSON at {@code pathAndQuery}, which starts with {@code /}. A missing resource is a config error. */
  public JsonNode get(String pathAndQuery) throws HarvesterException {
    JsonNode node = request(pathAndQuery);
    if (node == null) {
      throw new HarvesterException(ErrorClass.CONFIG, site.baseUrl() + withoutQuery(pathAndQuery) + " was not found (HTTP 404)");
    }
    return node;
  }

  /** Like {@link #get}, but null when the resource does not exist. */
  public JsonNode getOrNull(String pathAndQuery) throws HarvesterException {
    return request(pathAndQuery);
  }

  private JsonNode request(String pathAndQuery) throws HarvesterException {
    if (!pathAndQuery.startsWith("/")) {
      throw new IllegalArgumentException("path must start with /: " + pathAndQuery);
    }
    HttpRequest request = HttpRequest.newBuilder(URI.create(site.baseUrl() + pathAndQuery))
      .timeout(requestDeadline)
      .header("Accept", "application/json")
      .header("Authorization", site.authorization())
      .header("User-Agent", USER_AGENT)
      .GET()
      .build();
    for (int attempt = 0; ; attempt++) {
      HttpResponse<InputStream> response = send(request);
      byte[] body = read(response);
      response.headers().firstValue("Date").map(AtlassianClient::httpDate).ifPresent(time -> serverTime = time);
      int status = response.statusCode();
      if (status == 429 || status == 503) {
        long wait = retryAfter(response.headers().firstValue("Retry-After").orElse(null));
        if (attempt < MAX_RETRIES && wait <= MAX_INLINE_WAIT.toSeconds()) {
          pause(wait);
          continue;
        }
        throw new HarvesterException(ErrorClass.RATE_LIMITED, site.baseUrl() + " is limiting requests (HTTP " + status + ")", wait);
      }
      return interpret(status, response, body, pathAndQuery);
    }
  }

  private JsonNode interpret(int status, HttpResponse<InputStream> response, byte[] body, String pathAndQuery) throws HarvesterException {
    if (status >= 200 && status < 300) {
      try {
        JsonNode node = MAPPER.readTree(body);
        if (node == null || node.isMissingNode() || !(node.isObject() || node.isArray())) {
          throw new HarvesterException(ErrorClass.TRANSIENT, site.baseUrl() + " answered with something that is not JSON");
        }
        return node;
      } catch (JacksonException e) {
        throw new HarvesterException(ErrorClass.TRANSIENT, site.baseUrl() + " answered with something that is not JSON");
      }
    }
    if (status == 401 || status == 403) {
      throw new HarvesterException(ErrorClass.AUTH, site.baseUrl() + " rejected the credentials (HTTP " + status + ")");
    }
    if (status >= 300 && status < 400) {
      String location = response.headers().firstValue("Location").orElse("another address");
      throw new HarvesterException(ErrorClass.CONFIG, site.baseUrl() + " redirects to " + location + "; use that address as baseUrl");
    }
    if (status == 404) {
      return null;
    }
    if (status >= 400 && status < 500) {
      throw new HarvesterException(ErrorClass.CONFIG,
        site.baseUrl() + withoutQuery(pathAndQuery) + " refused the request (HTTP " + status + "): " + detail(body, status));
    }
    throw new HarvesterException(ErrorClass.TRANSIENT, site.baseUrl() + " failed (HTTP " + status + ")");
  }

  private HttpResponse<InputStream> send(HttpRequest request) throws HarvesterException {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException e) {
      throw new HarvesterException(ErrorClass.TRANSIENT, "could not reach " + site.baseUrl() + ": " + e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HarvesterException(ErrorClass.TRANSIENT, "interrupted while reading " + site.baseUrl());
    }
  }

  private byte[] read(HttpResponse<InputStream> response) throws HarvesterException {
    try (InputStream in = response.body()) {
      byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
      if (body.length > MAX_BODY_BYTES) {
        throw new HarvesterException(ErrorClass.TRANSIENT, site.baseUrl() + " sent a response over " + MAX_BODY_BYTES + " bytes");
      }
      return body;
    } catch (IOException e) {
      throw new HarvesterException(ErrorClass.TRANSIENT, "could not read from " + site.baseUrl() + ": " + e.getClass().getSimpleName());
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

  /** Jira puts problems in {@code errorMessages} and {@code errors}, Confluence in {@code message}. */
  private static String detail(byte[] body, int status) {
    try {
      JsonNode node = MAPPER.readTree(body);
      List<String> parts = new ArrayList<>();
      node.path("errorMessages").forEach(message -> parts.add(message.asString()));
      node.path("errors").properties().forEach(entry -> parts.add(entry.getKey() + ": " + entry.getValue().asString()));
      if (node.path("message").isString()) {
        parts.add(node.path("message").asString());
      }
      if (!parts.isEmpty()) {
        return String.join("; ", parts);
      }
    } catch (JacksonException | IllegalArgumentException e) {
      // Not JSON; fall through to the status alone.
    }
    return "HTTP " + status;
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

  private static String withoutQuery(String pathAndQuery) {
    int query = pathAndQuery.indexOf('?');
    return query < 0 ? pathAndQuery : pathAndQuery.substring(0, query);
  }
}
