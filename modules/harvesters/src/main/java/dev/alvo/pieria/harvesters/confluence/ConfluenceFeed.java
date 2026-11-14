package dev.alvo.pieria.harvesters.confluence;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.DocumentFormat;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.ProtocolLimits;
import dev.alvo.pieria.harvesters.atlassian.AtlassianClient;
import dev.alvo.pieria.harvesters.atlassian.Deployment;
import dev.alvo.pieria.harvesters.atlassian.OffsetPaging;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Confluence pages and blog posts as a change feed, through the CQL content search. Listings carry only
 * ids and versions; bodies are fetched afterwards, a batch at a time, with {@code id in (...)}. Cloud
 * pages by following {@code _links.next}, which carries a cursor; Data Center pages by offset through
 * {@link OffsetPaging}, and its {@code _links.next} only says whether more follow.
 */
public final class ConfluenceFeed implements ChangeFeed<JsonNode> {

  static final int LIST_PAGE = 100;
  static final int BODY_PAGE = 25;
  private static final String SEARCH = "/rest/api/content/search";
  private static final Pattern NUMERIC = Pattern.compile("\\d{1,19}");

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
    JsonNode user = client.get("/rest/api/user/current");
    // A rejected token can fall back to anonymous access, which would see only public pages.
    if ("anonymous".equals(user.path("type").asString(""))) {
      throw new HarvesterException(ErrorClass.AUTH, baseUrl + " treated the credentials as anonymous");
    }
    return client.serverTime();
  }

  /** Runs the scope query for one result, so a bad CQL fails with Confluence's own message. */
  public void probe() throws HarvesterException {
    search(config.scope(), null, 1, null);
  }

  @Override
  public Page<Change<JsonNode>> changes(Instant from, String token) throws HarvesterException {
    String bound = "";
    if (from != null) {
      long seconds = Math.max(0, Duration.between(from, client.serverTime()).toSeconds());
      bound = " AND lastmodified >= now(\"-" + Math.max(1, (seconds + 59) / 60) + "m\")";
    }
    Page<JsonNode> page = search(config.scope() + bound + " order by lastmodified asc", "version", LIST_PAGE, token);
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
    Page<JsonNode> page = search(config.scope() + " order by created asc", null, LIST_PAGE, token);
    return new Page<>(page.entries().stream().map(content -> content.path("id").asString()).toList(), page.next());
  }

  @Override
  public List<HarvestRecord.Item> load(List<Change<JsonNode>> changes) throws HarvesterException {
    List<String> ids = new ArrayList<>();
    for (Change<JsonNode> change : changes) {
      if (NUMERIC.matcher(change.id()).matches()) {
        ids.add(change.id());
      } else {
        System.err.println("confluence: skipped content with an unexpected id");
      }
    }
    List<HarvestRecord.Item> items = new ArrayList<>();
    if (ids.isEmpty()) {
      return items;
    }
    String cql = "id in (" + String.join(",", ids) + ")";
    String token = null;
    do {
      Page<JsonNode> page = search(cql, "body.storage,version,space", BODY_PAGE, token);
      for (JsonNode content : page.entries()) {
        items.add(document(content));
      }
      token = page.next();
    } while (token != null);
    return items;
  }

  private HarvestRecord.Item document(JsonNode content) throws HarvesterException {
    String id = content.path("id").asString();
    JsonNode body = content.path("body").path("storage").path("value");
    if (!body.isString()) {
      // An empty page has an empty body; a missing one would retire everything the page said.
      throw new HarvesterException(ErrorClass.TRANSIENT, "confluence returned content " + id + " without its body");
    }
    Instant when = time(content.path("version").path("when").asString(null));
    JsonNode number = content.path("version").path("number");
    if (when == null || !number.isIntegralNumber()) {
      throw new HarvesterException(ErrorClass.TRANSIENT, "confluence returned content " + id + " without its version");
    }
    String title = truncate(content.path("title").asString("").strip(), ProtocolLimits.MAX_TITLE_CHARS);
    if (title.isEmpty()) {
      title = id;
    }
    String text = StorageFormat.toMarkdown(body.asString(), baseUrl);
    if (text.isBlank()) {
      // An empty page still exists; reporting it keeps it out of the sweep's deletions.
      text = title;
    }
    String author = content.path("version").path("by").path("displayName").asString("").strip();
    String webui = content.path("_links").path("webui").asString("");
    String url = webui.startsWith("/") ? baseUrl + webui : baseUrl + "/pages/viewpage.action?pageId=" + id;
    Map<String, String> metadata = new LinkedHashMap<>();
    String space = content.path("space").path("key").asString("");
    if (!space.isEmpty()) {
      metadata.put("space", space);
    }
    String type = content.path("type").asString("");
    if (!type.isEmpty()) {
      metadata.put("type", type);
    }
    return new HarvestRecord.Document(id, String.valueOf(number.longValue()), title, url,
      author.isEmpty() ? null : truncate(author, ProtocolLimits.MAX_AUTHOR_CHARS), when, DocumentFormat.MARKDOWN,
      truncate(text, ProtocolLimits.MAX_TEXT_CHARS), metadata);
  }

  private Page<JsonNode> search(String cql, String expand, int limit, String token) throws HarvesterException {
    OffsetPaging.Position position = OffsetPaging.read(cloud ? null : token);
    String path;
    if (cloud && token != null) {
      path = token;
    } else {
      StringBuilder built = new StringBuilder(SEARCH).append("?cql=").append(AtlassianClient.encode(cql)).append("&limit=").append(limit);
      if (expand != null) {
        built.append("&expand=").append(AtlassianClient.encode(expand));
      }
      if (!cloud) {
        built.append("&start=").append(position.start());
      }
      path = built.toString();
    }
    JsonNode response = client.get(path);
    List<JsonNode> results = new ArrayList<>();
    response.path("results").forEach(results::add);
    if (cloud) {
      return new Page<>(results, nextPath(response.path("_links")));
    }
    List<String> ids = results.stream().map(content -> content.path("id").asString()).collect(Collectors.toList());
    OffsetPaging.requireContinuity(position, ids, "confluence");
    // The server may return fewer rows than asked for, so only its next link says whether more follow.
    if (response.path("_links").path("next").asString("").isBlank()) {
      return new Page<>(results, null);
    }
    if (results.isEmpty()) {
      throw new HarvesterException(ErrorClass.TRANSIENT, "confluence returned an empty page before the end of the results");
    }
    return new Page<>(results, OffsetPaging.next(position.start(), ids));
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

  private static String truncate(String value, int max) {
    return value.length() > max ? value.substring(0, max) : value;
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
