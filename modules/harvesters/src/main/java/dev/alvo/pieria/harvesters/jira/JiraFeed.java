package dev.alvo.pieria.harvesters.jira;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvesters.atlassian.AtlassianClient;
import dev.alvo.pieria.harvesters.atlassian.Deployment;
import dev.alvo.pieria.harvesters.atlassian.OffsetPaging;
import dev.alvo.pieria.harvesters.incremental.Change;
import dev.alvo.pieria.harvesters.incremental.ChangeFeed;
import dev.alvo.pieria.harvesters.incremental.Page;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Jira's issues as a change feed. Listings ask only for ids and times, and each changed issue is then
 * fetched on its own. Cloud pages with {@code nextPageToken}; Data Center pages by offset through
 * {@link OffsetPaging}. Changes use a relative {@code updated >= "-Nm"} bound, measured afresh from
 * the same instant for every page, which avoids JQL reading an absolute date in the account's timezone.
 */
public final class JiraFeed implements ChangeFeed<JsonNode> {

  static final int LIST_PAGE = 1000;
  static final int COMMENT_PAGE = 100;

  private final JiraConfig config;
  private final AtlassianClient client;
  private final boolean cloud;

  public JiraFeed(JiraConfig config, AtlassianClient client) {
    this.config = config;
    this.client = client;
    this.cloud = config.site().deployment() == Deployment.CLOUD;
  }

  private String api() {
    return cloud ? "/rest/api/3" : "/rest/api/2";
  }

  @Override
  public Instant begin() throws HarvesterException {
    client.get(api() + "/myself");
    return client.serverTime();
  }

  /** Runs the change query for one row, so a bad JQL fails with Jira's own message. */
  public void probe() throws HarvesterException {
    search(changeJql(null), "updated", 1, null);
  }

  @Override
  public Page<Change<JsonNode>> changes(Instant from, String token) throws HarvesterException {
    Page<JsonNode> page = search(changeJql(from), "updated", LIST_PAGE, token);
    List<Change<JsonNode>> changes = new ArrayList<>();
    for (JsonNode issue : page.entries()) {
      Instant updated = IssueThreads.time(issue.path("fields").path("updated").asString(null));
      if (updated == null) {
        System.err.println("jira: skipped " + issue.path("key").asString() + ", which has no readable updated time");
        continue;
      }
      changes.add(new Change<>(issue.path("id").asString(), String.valueOf(updated.toEpochMilli()), updated, issue));
    }
    return new Page<>(changes, page.next());
  }

  @Override
  public Page<String> liveIds(String token) throws HarvesterException {
    Page<JsonNode> page = search("(" + config.jql() + ") ORDER BY created ASC, key ASC", "created", LIST_PAGE, token);
    return new Page<>(page.entries().stream().map(issue -> issue.path("id").asString()).toList(), page.next());
  }

  @Override
  public List<HarvestRecord.Item> load(List<Change<JsonNode>> changes) throws HarvesterException {
    List<HarvestRecord.Item> items = new ArrayList<>();
    for (Change<JsonNode> change : changes) {
      String id = AtlassianClient.encode(change.id());
      JsonNode issue = client.getOrNull(api() + "/issue/" + id + "?fields=" + AtlassianClient.encode(IssueThreads.FIELDS)
        + "&expand=renderedFields");
      List<JsonNode> comments = issue == null ? null : comments(id);
      if (comments != null) {
        items.add(IssueThreads.build(config.site().baseUrl(), issue, comments));
      }
    }
    return items;
  }

  /** Every comment, or null when the issue has gone. */
  private List<JsonNode> comments(String encodedId) throws HarvesterException {
    List<JsonNode> comments = new ArrayList<>();
    int startAt = 0;
    while (true) {
      JsonNode page = client.getOrNull(api() + "/issue/" + encodedId + "/comment?startAt=" + startAt
        + "&maxResults=" + COMMENT_PAGE + "&orderBy=created&expand=renderedBody");
      if (page == null) {
        return null;
      }
      JsonNode batch = page.path("comments");
      batch.forEach(comments::add);
      startAt += batch.size();
      if (batch.isEmpty() || startAt >= page.path("total").asInt(0)) {
        return comments;
      }
    }
  }

  private String changeJql(Instant from) {
    String bound = "";
    if (from != null) {
      long seconds = Math.max(0, Duration.between(from, client.serverTime()).toSeconds());
      bound = " AND updated >= \"-" + Math.max(1, (seconds + 59) / 60) + "m\"";
    }
    return "(" + config.jql() + ")" + bound + " ORDER BY updated ASC, key ASC";
  }

  private Page<JsonNode> search(String jql, String fields, int maxResults, String token) throws HarvesterException {
    StringBuilder path = new StringBuilder(cloud ? "/rest/api/3/search/jql" : "/rest/api/2/search")
      .append("?jql=").append(AtlassianClient.encode(jql))
      .append("&fields=").append(AtlassianClient.encode(fields))
      .append("&maxResults=").append(maxResults);
    OffsetPaging.Position position = OffsetPaging.read(cloud ? null : token);
    if (cloud) {
      if (token != null) {
        path.append("&nextPageToken=").append(AtlassianClient.encode(token));
      }
    } else {
      path.append("&startAt=").append(position.start());
    }
    JsonNode response = client.get(path.toString());
    List<JsonNode> issues = new ArrayList<>();
    response.path("issues").forEach(issues::add);
    if (cloud) {
      String nextToken = response.path("nextPageToken").asString(null);
      boolean hasNext = nextToken != null && !nextToken.isBlank();
      if (response.path("isLast").isBoolean() && !response.path("isLast").booleanValue() && !hasNext) {
        throw new HarvesterException(ErrorClass.TRANSIENT, "jira said more results follow but gave no way to fetch them");
      }
      return new Page<>(issues, response.path("isLast").asBoolean(false) || !hasNext ? null : nextToken);
    }
    List<String> ids = issues.stream().map(issue -> issue.path("id").asString()).toList();
    OffsetPaging.requireContinuity(position, ids, "jira");
    // The server may return fewer rows than asked for, so only the total says where the end is.
    int total = response.path("total").asInt(0);
    if (position.start() + issues.size() >= total) {
      return new Page<>(issues, null);
    }
    if (issues.isEmpty()) {
      throw new HarvesterException(ErrorClass.TRANSIENT, "jira returned an empty page before the end of the results");
    }
    return new Page<>(issues, OffsetPaging.next(position.start(), ids));
  }
}
