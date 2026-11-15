package dev.alvo.pieria.harvesters.atlassian;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvesters.http.JsonTransport;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads JSON from one Atlassian site. Requests only ever go to the site's own address, and redirects
 * are never followed, so the credentials can't reach another host.
 */
public final class AtlassianClient {

  public static final int MAX_BODY_BYTES = JsonTransport.MAX_BODY_BYTES;
  public static final int MAX_RETRIES = JsonTransport.MAX_RETRIES;
  public static final Duration MAX_INLINE_WAIT = JsonTransport.MAX_INLINE_WAIT;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final AtlassianSite site;
  private final JsonTransport transport;

  public AtlassianClient(AtlassianSite site, Duration requestDeadline) {
    this.site = site;
    this.transport = new JsonTransport(site.baseUrl(), requestDeadline);
  }

  public AtlassianSite site() {
    return site;
  }

  /** Encodes a query value; spaces become {@code %20}. */
  public static String encode(String value) {
    return JsonTransport.encode(value);
  }

  /** The site's clock, from the latest response's {@code Date} header, else the local clock. */
  public Instant serverTime() {
    return transport.serverTime();
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
    HttpRequest request = transport.request(URI.create(site.baseUrl() + pathAndQuery))
      .header("Authorization", site.authorization())
      .GET()
      .build();
    JsonTransport.Response response = transport.send(request);
    return interpret(response, pathAndQuery);
  }

  private JsonNode interpret(JsonTransport.Response response, String pathAndQuery) throws HarvesterException {
    int status = response.status();
    if (status >= 200 && status < 300) {
      return JsonTransport.json(response.body(), site.baseUrl());
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
        site.baseUrl() + withoutQuery(pathAndQuery) + " refused the request (HTTP " + status + "): " + detail(response.body(), status));
    }
    throw new HarvesterException(ErrorClass.TRANSIENT, site.baseUrl() + " failed (HTTP " + status + ")");
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

  private static String withoutQuery(String pathAndQuery) {
    int query = pathAndQuery.indexOf('?');
    return query < 0 ? pathAndQuery : pathAndQuery.substring(0, query);
  }
}
