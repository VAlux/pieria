package dev.alvo.pieria.harvesters.confluence;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.DocumentFormat;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.ProtocolLimits;
import dev.alvo.pieria.harvesters.atlassian.AtlassianClient;
import dev.alvo.pieria.harvesters.atlassian.Deployment;
import dev.alvo.pieria.harvesters.incremental.Change;
import dev.alvo.pieria.harvesters.incremental.ChangeFeed;
import dev.alvo.pieria.harvesters.incremental.Page;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Confluence pages and blog posts as a change feed, through the CQL content search. Cloud pages by
 * following {@code _links.next}, which carries a cursor; Data Center pages by offset, stepping back
 * {@link #PAGE_OVERLAP} rows. A changes page carries each page's storage body, so loading one costs no
 * further request.
 */
public final class ConfluenceFeed implements ChangeFeed<JsonNode> {

  static final int CHANGE_PAGE = 25;
  static final int ID_PAGE = 100;
  static final int PAGE_OVERLAP = 10;
  private static final String SEARCH = "/rest/api/content/search";

  private final ConfluenceConfig config;
  private final AtlassianClient client;
  private final boolean cloud;
  private final String baseUrl;

  public ConfluenceFeed(ConfluenceConfig config, AtlassianClient client) {
    this.config = config;
    this.client = client;
    this.cloud = config.site().deployment() == Deployment.CLOUD;
    this.baseUrl = config.site().baseUrl();
  }

  @Override
  public Instant begin() throws HarvesterException {
    client.get("/rest/api/user/current");
    return client.serverTime();
  }

  /** Runs the scope query for one result, so a bad CQL fails with Confluence's own message. */
  public void probe() throws HarvesterException {
    search(config.scope(), null, 1, null);
  }

  @Override
  public Page<Change<JsonNode>> changes(Duration lookback, String token) throws HarvesterException {
    String bound = "";
    if (lookback != null) {
      long minutes = Math.max(1, (lookback.toSeconds() + 59) / 60);
      bound = " AND lastmodified >= now(\"-" + minutes + "m\")";
    }
    Page<JsonNode> page = search(config.scope() + bound + " order by lastmodified asc", "body.storage,version,space", CHANGE_PAGE, token);
    List<Change<JsonNode>> changes = new ArrayList<>();
    for (JsonNode content : page.entries()) {
      Instant when = time(content.path("version").path("when").asString(null));
      JsonNode number = content.path("version").path("number");
      if (when == null || !number.isIntegralNumber()) {
        System.err.println("confluence: skipped " + content.path("id").asString() + ", which has no readable version");
        continue;
      }
      changes.add(new Change<>(content.path("id").asString(), String.valueOf(number.longValue()), when, content));
    }
    return new Page<>(changes, page.next());
  }

  @Override
  public Page<String> liveIds(String token) throws HarvesterException {
    Page<JsonNode> page = search(config.scope() + " order by created asc", null, ID_PAGE, token);
    return new Page<>(page.entries().stream().map(content -> content.path("id").asString()).toList(), page.next());
  }

  @Override
  public HarvestRecord.Item load(Change<JsonNode> change) {
    JsonNode content = change.data();
    String title = content.path("title").asString("").strip();
    if (title.isEmpty()) {
      title = change.id();
    }
    if (title.length() > ProtocolLimits.MAX_TITLE_CHARS) {
      title = title.substring(0, ProtocolLimits.MAX_TITLE_CHARS);
    }
    String text = StorageFormat.toMarkdown(content.path("body").path("storage").path("value").asString(""), baseUrl);
    if (text.isBlank()) {
      // An empty page still exists; reporting it keeps it out of the sweep's deletions.
      text = title;
    }
    if (text.length() > ProtocolLimits.MAX_TEXT_CHARS) {
      text = text.substring(0, ProtocolLimits.MAX_TEXT_CHARS);
    }
    String author = content.path("version").path("by").path("displayName").asString(null);
    if (author != null && (author.isBlank() || author.length() > ProtocolLimits.MAX_AUTHOR_CHARS)) {
      author = author.isBlank() ? null : author.substring(0, ProtocolLimits.MAX_AUTHOR_CHARS);
    }
    String webui = content.path("_links").path("webui").asString("");
    String url = webui.startsWith("/") ? baseUrl + webui : baseUrl + "/pages/viewpage.action?pageId=" + AtlassianClient.encode(change.id());
    Map<String, String> metadata = new LinkedHashMap<>();
    String space = content.path("space").path("key").asString("");
    if (!space.isEmpty()) {
      metadata.put("space", space);
    }
    String type = content.path("type").asString("");
    if (!type.isEmpty()) {
      metadata.put("type", type);
    }
    return new HarvestRecord.Document(change.id(), change.version(), title, url, author, change.changedAt(), DocumentFormat.MARKDOWN,
      text, metadata);
  }

  private Page<JsonNode> search(String cql, String expand, int limit, String token) throws HarvesterException {
    String path;
    int start = 0;
    if (cloud && token != null) {
      path = token;
    } else {
      StringBuilder built = new StringBuilder(SEARCH).append("?cql=").append(AtlassianClient.encode(cql)).append("&limit=").append(limit);
      if (expand != null) {
        built.append("&expand=").append(AtlassianClient.encode(expand));
      }
      if (!cloud) {
        start = token == null ? 0 : Integer.parseInt(token);
        built.append("&start=").append(start);
      }
      path = built.toString();
    }
    JsonNode response = client.get(path);
    List<JsonNode> results = new ArrayList<>();
    response.path("results").forEach(results::add);
    String next;
    if (cloud) {
      next = nextPath(response.path("_links"));
    } else {
      next = results.size() < limit ? null : String.valueOf(Math.max(start + 1, start + results.size() - PAGE_OVERLAP));
    }
    return new Page<>(results, next);
  }

  /** {@code _links.next} as a path under {@code baseUrl}, or null on the last page. */
  private String nextPath(JsonNode links) throws HarvesterException {
    String next = links.path("next").asString(null);
    if (next == null || next.isBlank()) {
      return null;
    }
    String context = links.path("context").asString("");
    if (next.startsWith("/")) {
      if (!context.isEmpty() && next.startsWith(context + "/")) {
        next = next.substring(context.length());
      }
      return next;
    }
    if (next.startsWith(baseUrl + "/")) {
      return next.substring(baseUrl.length());
    }
    throw new HarvesterException(ErrorClass.TRANSIENT, "confluence returned a next link outside baseUrl");
  }

  private static Instant time(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
