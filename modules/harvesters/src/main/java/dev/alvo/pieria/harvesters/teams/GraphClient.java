package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvesters.http.JsonTransport;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;

/**
 * Reads Microsoft Graph v1.0 as the signed-in user. Requests only go under {@code graphUrl}, and next
 * links are checked against it, so the access token can't reach another host.
 */
public final class GraphClient {

  private static final String LABEL = "Microsoft Graph";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String root;
  private final String accessToken;
  private final JsonTransport transport;

  public GraphClient(String graphUrl, String accessToken, JsonTransport transport) {
    this.root = graphUrl + "/v1.0";
    this.accessToken = accessToken;
    this.transport = transport;
  }

  /** The JSON at {@code pathAndQuery}, which starts with {@code /}. A missing resource is a config error. */
  public JsonNode get(String pathAndQuery) throws HarvesterException {
    JsonNode node = getOrNull(pathAndQuery);
    if (node == null) {
      throw new HarvesterException(ErrorClass.CONFIG, withoutQuery(pathAndQuery) + " was not found");
    }
    return node;
  }

  /** Like {@link #get}, but null when the resource does not exist. */
  public JsonNode getOrNull(String pathAndQuery) throws HarvesterException {
    if (!pathAndQuery.startsWith("/")) {
      throw new IllegalArgumentException("path must start with /: " + pathAndQuery);
    }
    return request(root + pathAndQuery, pathAndQuery);
  }

  /** The page an {@code @odata.nextLink} points at. */
  public JsonNode follow(String link) throws HarvesterException {
    if (link == null || !link.startsWith(root + "/")) {
      throw new HarvesterException(ErrorClass.TRANSIENT, "graph returned a next link outside graphUrl");
    }
    String pathAndQuery = link.substring(root.length());
    JsonNode node = request(link, pathAndQuery);
    if (node == null) {
      throw new HarvesterException(ErrorClass.CONFIG, withoutQuery(pathAndQuery) + " was not found");
    }
    return node;
  }

  /** Graph's clock, from the latest response's {@code Date} header, else the local clock. */
  public Instant serverTime() {
    return transport.serverTime();
  }

  private JsonNode request(String url, String pathAndQuery) throws HarvesterException {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      throw new HarvesterException(ErrorClass.TRANSIENT, "graph returned an address that is not valid");
    }
    HttpRequest request = transport.request(uri).header("Authorization", "Bearer " + accessToken).GET().build();
    JsonTransport.Response response = transport.send(request);
    int status = response.status();
    if (status >= 200 && status < 300) {
      return JsonTransport.json(response.body(), LABEL);
    }
    if (status == 401) {
      throw new HarvesterException(ErrorClass.AUTH, LABEL + " rejected the sign-in (HTTP 401)");
    }
    if (status == 403) {
      throw new HarvesterException(ErrorClass.AUTH, LABEL + " denied access (HTTP 403): " + detail(response.body(), status));
    }
    if (status == 404) {
      return null;
    }
    if (status >= 400 && status < 500) {
      throw new HarvesterException(ErrorClass.CONFIG,
        LABEL + " refused " + withoutQuery(pathAndQuery) + " (HTTP " + status + "): " + detail(response.body(), status));
    }
    throw new HarvesterException(ErrorClass.TRANSIENT, LABEL + " failed (HTTP " + status + ")");
  }

  private static String detail(byte[] body, int status) {
    try {
      JsonNode message = MAPPER.readTree(body).path("error").path("message");
      if (message.isString() && !message.asString().isBlank()) {
        return message.asString().lines().findFirst().orElse("").strip();
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
