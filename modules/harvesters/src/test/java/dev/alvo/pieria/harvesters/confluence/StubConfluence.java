package dev.alvo.pieria.harvesters.confluence;

import dev.alvo.pieria.harvesters.JsonStub;
import dev.alvo.pieria.harvesters.JsonStub.Reply;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Confluence on localhost. Cloud lives under {@code /wiki} and pages with a {@code cursor} in
 * {@code _links.next}; Data Center has no context path and pages with {@code start}.
 */
final class StubConfluence implements AutoCloseable {

  static final String EMAIL = "bot@example.com";
  static final String TOKEN = "conf-token";
  private static final Pattern RELATIVE = Pattern.compile("lastmodified >= now\\(\"-(\\d+)m\"\\)");
  private static final Pattern ORDER = Pattern.compile("order by (\\w+)");
  private static final Pattern SPACES = Pattern.compile("space in \\(([^)]*)\\)");
  private static final Pattern IDS = Pattern.compile("^id in \\(([^)]*)\\)");

  static final class Content {
    final String id;
    final String space;
    final String title;
    final Instant created;
    volatile Instant modified;
    volatile int version = 1;
    volatile String storage;

    Content(String id, String space, String title, Instant created, String storage) {
      this.id = id;
      this.space = space;
      this.title = title;
      this.created = created;
      this.modified = created;
      this.storage = storage;
    }

    void edit(Instant when, String storage) {
      this.modified = when;
      this.version++;
      this.storage = storage;
    }
  }

  final boolean cloud;
  private final JsonStub stub = JsonStub.start();
  private final Map<String, Content> contents = new ConcurrentHashMap<>();
  private volatile boolean revoked;
  private volatile String foreignNext;
  private volatile int limitCap = Integer.MAX_VALUE;
  private volatile boolean anonymous;
  private volatile boolean dropBodies;
  private volatile Runnable afterFirstSearchPage;

  StubConfluence(boolean cloud) {
    this.cloud = cloud;
    stub.route(context() + "/rest/api/user/current",
      request -> authorized(request)
        ? Reply.json(Map.of("type", anonymous ? "anonymous" : "known", "displayName", "Bot"))
        : Reply.status(401));
    stub.route(context() + "/rest/api/content/search", this::search);
  }

  private String context() {
    return cloud ? "/wiki" : "";
  }

  /** The address a user would configure: the bare host on Cloud, where the harvester adds /wiki. */
  String baseUrl() {
    return stub.baseUrl();
  }

  String searchPath() {
    return context() + "/rest/api/content/search";
  }

  List<JsonStub.Request> requests(String path) {
    return stub.requests(path);
  }

  Content add(String id, String space, String title, Instant created, String storage) {
    Content content = new Content(id, space, title, created, storage);
    contents.put(id, content);
    return content;
  }

  void delete(String id) {
    contents.remove(id);
  }

  void revoke() {
    revoked = true;
  }

  void capLimit(int cap) {
    limitCap = cap;
  }

  /** The token is accepted, but the user is anonymous. */
  void anonymous() {
    anonymous = true;
  }

  /** Results leave out the body even when it is asked for. */
  void dropBodies() {
    dropBodies = true;
  }

  void afterFirstSearchPage(Runnable action) {
    afterFirstSearchPage = action;
  }

  /** Every next link points at this absolute address instead. */
  void foreignNext(String url) {
    foreignNext = url;
  }

  private boolean authorized(JsonStub.Request request) {
    String expected = cloud
      ? "Basic " + Base64.getEncoder().encodeToString((EMAIL + ":" + TOKEN).getBytes(StandardCharsets.UTF_8))
      : "Bearer " + TOKEN;
    return !revoked && expected.equals(request.header("Authorization"));
  }

  private Reply search(JsonStub.Request request) {
    if (!authorized(request)) {
      return Reply.status(401);
    }
    String cql = request.query().getOrDefault("cql", "");
    Instant cutoff = null;
    Matcher relative = RELATIVE.matcher(cql);
    if (relative.find()) {
      cutoff = Instant.now().minusSeconds(Long.parseLong(relative.group(1)) * 60);
    }
    List<String> ids = new ArrayList<>();
    Matcher idList = IDS.matcher(cql);
    if (idList.find()) {
      for (String id : idList.group(1).split(",")) {
        ids.add(id.strip());
      }
    }
    List<String> spaces = new ArrayList<>();
    Matcher space = SPACES.matcher(cql);
    if (space.find()) {
      for (String key : space.group(1).split(",")) {
        spaces.add(key.strip().replace("\"", ""));
      }
    }
    Matcher order = ORDER.matcher(cql);
    Comparator<Content> comparator = order.find() && order.group(1).equals("created")
      ? Comparator.comparing((Content content) -> content.created)
      : Comparator.comparing((Content content) -> content.modified);
    Instant bound = cutoff;
    List<Content> matching = contents.values().stream()
      .filter(content -> bound == null || !content.modified.isBefore(bound))
      .filter(content -> spaces.isEmpty() || spaces.contains(content.space))
      .filter(content -> ids.isEmpty() || ids.contains(content.id))
      .sorted(comparator.thenComparing(content -> content.id))
      .toList();
    int limit = Math.min(limitCap, Integer.parseInt(request.query().getOrDefault("limit", "25")));
    String cursor = request.query().get("cursor");
    int start = cloud
      ? (cursor == null ? 0 : Integer.parseInt(cursor.substring(1)))
      : Integer.parseInt(request.query().getOrDefault("start", "0"));
    boolean body = !dropBodies && request.query().getOrDefault("expand", "").contains("body.storage");
    List<Map<String, Object>> results = matching.subList(Math.min(start, matching.size()), Math.min(matching.size(), start + limit))
      .stream().map(content -> json(content, body)).toList();
    Map<String, Object> links = new LinkedHashMap<>();
    links.put("base", stub.baseUrl() + context());
    links.put("context", context());
    if (start + limit < matching.size()) {
      String query = "cql=" + java.net.URLEncoder.encode(cql, StandardCharsets.UTF_8) + "&limit=" + limit
        + (request.query().containsKey("expand") ? "&expand=" + request.query().get("expand") : "");
      String next = cloud
        ? context() + "/rest/api/content/search?" + query + "&cursor=c" + (start + limit)
        : "/rest/api/content/search?" + query + "&start=" + (start + limit);
      links.put("next", foreignNext != null ? foreignNext : next);
    }
    Map<String, Object> reply = new LinkedHashMap<>();
    reply.put("results", results);
    reply.put("start", start);
    reply.put("limit", limit);
    reply.put("size", results.size());
    reply.put("_links", links);
    Reply answer = Reply.json(reply);
    Runnable action = afterFirstSearchPage;
    if (action != null && start == 0) {
      afterFirstSearchPage = null;
      action.run();
    }
    return answer;
  }

  private Map<String, Object> json(Content content, boolean body) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", content.id);
    json.put("type", "page");
    json.put("title", content.title);
    json.put("space", Map.of("key", content.space));
    json.put("version", Map.of("number", content.version, "when", content.modified.toString(), "by", Map.of("displayName", "Ada")));
    if (body) {
      json.put("body", Map.of("storage", Map.of("value", content.storage, "representation", "storage")));
    }
    json.put("_links", Map.of("webui", "/spaces/" + content.space + "/pages/" + content.id));
    return json;
  }

  @Override
  public void close() {
    stub.close();
  }
}
