package dev.alvo.pieria.harvesters.jira;

import dev.alvo.pieria.harvesters.JsonStub;
import dev.alvo.pieria.harvesters.JsonStub.Reply;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
 * A Jira on localhost, speaking the Cloud ({@code /rest/api/3/search/jql}) or Data Center
 * ({@code /rest/api/2/search}) API. It understands as much JQL as the harvester writes: a relative
 * {@code updated >= "-Nm"} bound and the {@code ORDER BY} field.
 */
final class StubJira implements AutoCloseable {

  static final String EMAIL = "bot@example.com";
  static final String TOKEN = "jira-token";
  static final DateTimeFormatter JIRA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZone(ZoneOffset.UTC);
  private static final Pattern RELATIVE = Pattern.compile("updated >= \"-(\\d+)m\"");
  private static final Pattern ORDER = Pattern.compile("ORDER BY (\\w+)");

  static final class Issue {
    final String id;
    final String key;
    volatile String summary;
    final Instant created;
    volatile Instant updated;
    volatile String descriptionHtml;
    volatile String status = "Open";
    final List<Map<String, Object>> comments = new ArrayList<>();

    Issue(String id, String key, String summary, Instant created, Instant updated, String descriptionHtml) {
      this.id = id;
      this.key = key;
      this.summary = summary;
      this.created = created;
      this.updated = updated;
      this.descriptionHtml = descriptionHtml;
    }

    Issue comment(String id, String author, Instant created, String html) {
      Map<String, Object> comment = new LinkedHashMap<>();
      comment.put("id", id);
      comment.put("author", Map.of("displayName", author));
      comment.put("created", JIRA_TIME.format(created));
      comment.put("renderedBody", html);
      comments.add(comment);
      return this;
    }
  }

  final boolean cloud;
  private final JsonStub stub = JsonStub.start();
  private final Map<String, Issue> issues = new ConcurrentHashMap<>();
  private volatile Runnable afterFirstSearchPage;
  private volatile boolean revoked;
  private volatile int maxResultsCap = Integer.MAX_VALUE;
  private volatile boolean brokenLastPage;

  StubJira(boolean cloud) {
    this.cloud = cloud;
    String version = cloud ? "3" : "2";
    stub.route("/rest/api/" + version + "/myself", request -> authorized(request) ? Reply.json(Map.of("displayName", "Bot")) : Reply.status(401));
    stub.route(cloud ? "/rest/api/3/search/jql" : "/rest/api/2/search", this::search);
  }

  String baseUrl() {
    return stub.baseUrl();
  }

  String version() {
    return cloud ? "3" : "2";
  }

  String searchPath() {
    return cloud ? "/rest/api/3/search/jql" : "/rest/api/2/search";
  }

  List<JsonStub.Request> requests(String path) {
    return stub.requests(path);
  }

  Issue add(String id, String key, String summary, Instant created, Instant updated, String descriptionHtml) {
    Issue issue = new Issue(id, key, summary, created, updated, descriptionHtml);
    issues.put(id, issue);
    stub.route("/rest/api/" + version() + "/issue/" + id + "/comment", request -> comments(id, request));
    stub.route("/rest/api/" + version() + "/issue/" + id, request -> issue(id, request));
    return issue;
  }

  void delete(String id) {
    issues.remove(id);
  }

  void afterFirstSearchPage(Runnable action) {
    this.afterFirstSearchPage = action;
  }

  void revoke() {
    this.revoked = true;
  }

  /** Returns at most this many rows a page, as a Data Center admin setting can. */
  void capMaxResults(int cap) {
    this.maxResultsCap = cap;
  }

  /** Cloud pages say {@code isLast: false} but carry no token. */
  void breakLastPage() {
    this.brokenLastPage = true;
  }

  private Reply issue(String id, JsonStub.Request request) {
    if (!authorized(request)) {
      return Reply.status(401);
    }
    Issue issue = issues.get(id);
    if (issue == null) {
      return new Reply(404, Map.of("Content-Type", "application/json"), "{\"errorMessages\":[\"Issue does not exist\"]}");
    }
    return Reply.json(json(issue, request.query().getOrDefault("expand", "").contains("renderedFields")));
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
    String jql = request.query().getOrDefault("jql", "");
    if (jql.contains("nope")) {
      return new Reply(400, Map.of("Content-Type", "application/json"), "{\"errorMessages\":[\"Field 'nope' does not exist\"]}");
    }
    Instant cutoff = null;
    Matcher relative = RELATIVE.matcher(jql);
    if (relative.find()) {
      cutoff = Instant.now().minusSeconds(Long.parseLong(relative.group(1)) * 60);
    }
    Matcher order = ORDER.matcher(jql);
    Comparator<Issue> comparator = order.find() && order.group(1).equals("created")
      ? Comparator.comparing((Issue issue) -> issue.created)
      : Comparator.comparing((Issue issue) -> issue.updated);
    Instant bound = cutoff;
    List<Issue> matching = issues.values().stream()
      .filter(issue -> bound == null || !issue.updated.isBefore(bound))
      .sorted(comparator.thenComparing(issue -> issue.key))
      .toList();
    int maxResults = Math.min(maxResultsCap, Integer.parseInt(request.query().getOrDefault("maxResults", "50")));
    int start = Integer.parseInt(cloud ? request.query().getOrDefault("nextPageToken", "0") : request.query().getOrDefault("startAt", "0"));
    boolean rendered = request.query().getOrDefault("expand", "").contains("renderedFields");
    List<Map<String, Object>> page = matching.subList(Math.min(start, matching.size()), Math.min(matching.size(), start + maxResults))
      .stream().map(issue -> json(issue, rendered)).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("issues", page);
    if (cloud) {
      boolean last = start + maxResults >= matching.size();
      body.put("isLast", last);
      if (!last && !brokenLastPage) {
        body.put("nextPageToken", String.valueOf(start + maxResults));
      }
    } else {
      body.put("startAt", start);
      body.put("maxResults", maxResults);
      body.put("total", matching.size());
    }
    Reply reply = Reply.json(body);
    Runnable action = afterFirstSearchPage;
    if (action != null && start == 0) {
      afterFirstSearchPage = null;
      action.run();
    }
    return reply;
  }

  private Map<String, Object> json(Issue issue, boolean rendered) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("summary", issue.summary);
    fields.put("created", JIRA_TIME.format(issue.created));
    fields.put("updated", JIRA_TIME.format(issue.updated));
    fields.put("status", Map.of("name", issue.status));
    fields.put("issuetype", Map.of("name", "Task"));
    fields.put("project", Map.of("key", issue.key.substring(0, issue.key.indexOf('-'))));
    fields.put("reporter", Map.of("displayName", "Reporter"));
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", issue.id);
    json.put("key", issue.key);
    json.put("fields", fields);
    if (rendered) {
      json.put("renderedFields", Map.of("description", issue.descriptionHtml == null ? "" : issue.descriptionHtml));
    }
    return json;
  }

  private Reply comments(String id, JsonStub.Request request) {
    if (!authorized(request)) {
      return Reply.status(401);
    }
    Issue issue = issues.get(id);
    if (issue == null) {
      return new Reply(404, Map.of("Content-Type", "application/json"), "{\"errorMessages\":[\"Issue does not exist\"]}");
    }
    int start = Integer.parseInt(request.query().getOrDefault("startAt", "0"));
    int max = Integer.parseInt(request.query().getOrDefault("maxResults", "50"));
    List<Map<String, Object>> all = List.copyOf(issue.comments);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("startAt", start);
    body.put("maxResults", max);
    body.put("total", all.size());
    body.put("comments", all.subList(Math.min(start, all.size()), Math.min(all.size(), start + max)));
    return Reply.json(body);
  }

  @Override
  public void close() {
    stub.close();
  }
}
