package dev.alvo.pieria.harvesters.jira;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvesters.atlassian.AtlassianClient;
import dev.alvo.pieria.harvesters.atlassian.Deployment;
import dev.alvo.pieria.harvesters.incremental.Change;
import dev.alvo.pieria.harvesters.incremental.ChangeFeed;
import dev.alvo.pieria.harvesters.incremental.Page;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Jira's issues as a change feed. Cloud pages with {@code nextPageToken}; Data Center pages by offset,
 * stepping back {@link #PAGE_OVERLAP} rows so an issue edited mid-listing can't push another past the
 * page boundary. Changes use a relative {@code updated >= "-Nm"} bound, which avoids JQL reading an
 * absolute date in the account's own timezone.
 */
public final class JiraFeed implements ChangeFeed<JsonNode> {

  static final int CHANGE_PAGE = 50;
  static final int ID_PAGE = 1000;
  static final int COMMENT_PAGE = 100;
  static final int PAGE_OVERLAP = 10;

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
    search(changeJql(null), IssueThreads.FIELDS, false, 1, null);
  }

  @Override
  public Page<Change<JsonNode>> changes(Duration lookback, String token) throws HarvesterException {
    Page<JsonNode> page = search(changeJql(lookback), IssueThreads.FIELDS, true, CHANGE_PAGE, token);
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
    Page<JsonNode> page = search("(" + config.jql() + ") ORDER BY created ASC, key ASC", "created", false, ID_PAGE, token);
    return new Page<>(page.entries().stream().map(issue -> issue.path("id").asString()).toList(), page.next());
  }

  @Override
  public HarvestRecord.Item load(Change<JsonNode> change) throws HarvesterException {
    List<JsonNode> comments = new ArrayList<>();
    int startAt = 0;
    while (true) {
      JsonNode page = client.getOrNull(api() + "/issue/" + AtlassianClient.encode(change.id()) + "/comment?startAt=" + startAt
        + "&maxResults=" + COMMENT_PAGE + "&orderBy=created&expand=renderedBody");
      if (page == null) {
        return null;
      }
      JsonNode batch = page.path("comments");
      batch.forEach(comments::add);
      startAt += batch.size();
      if (batch.isEmpty() || startAt >= page.path("total").asInt(0)) {
        break;
      }
    }
    return IssueThreads.build(config.site().baseUrl(), change.data(), comments);
  }

  private String changeJql(Duration lookback) {
    String bound = "";
    if (lookback != null) {
      long minutes = Math.max(1, (lookback.toSeconds() + 59) / 60);
      bound = " AND updated >= \"-" + minutes + "m\"";
    }
    return "(" + config.jql() + ")" + bound + " ORDER BY updated ASC, key ASC";
  }

  private Page<JsonNode> search(String jql, String fields, boolean rendered, int maxResults, String token) throws HarvesterException {
    StringBuilder path = new StringBuilder(cloud ? "/rest/api/3/search/jql" : "/rest/api/2/search")
      .append("?jql=").append(AtlassianClient.encode(jql))
      .append("&fields=").append(AtlassianClient.encode(fields))
      .append("&maxResults=").append(maxResults);
    if (rendered) {
      path.append("&expand=renderedFields");
    }
    int startAt = 0;
    if (cloud) {
      if (token != null) {
        path.append("&nextPageToken=").append(AtlassianClient.encode(token));
      }
    } else {
      startAt = token == null ? 0 : Integer.parseInt(token);
      path.append("&startAt=").append(startAt);
    }
    JsonNode response = client.get(path.toString());
    List<JsonNode> issues = new ArrayList<>();
    response.path("issues").forEach(issues::add);
    String next;
    if (cloud) {
      String nextToken = response.path("nextPageToken").asString(null);
      next = response.path("isLast").asBoolean(false) || nextToken == null || nextToken.isBlank() ? null : nextToken;
    } else {
      int total = response.path("total").asInt(0);
      boolean last = issues.size() < maxResults || startAt + issues.size() >= total;
      next = last ? null : String.valueOf(Math.max(startAt + 1, startAt + issues.size() - PAGE_OVERLAP));
    }
    return new Page<>(issues, next);
  }
}
